/**
 * Web Audio playback of a score: a click on every beat (higher and louder on downbeats) and a short tone
 * per note with its gain from the accent. Events are scheduled with a lookahead timer so long scores do
 * not create thousands of nodes at once; the playhead follows the audio clock through msToTick.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Score } from '@/types/score';
import { msToTick, playbackPlan, tickToMs } from '@/lib/score';
import type { PlaybackPlan } from '@/lib/score';

const LOOKAHEAD_S = 1.0;
const TIMER_MS = 120;
const START_DELAY_S = 0.08;
const CLICK_S = 0.03;
const NOTE_HZ = 440;
const NOTE_MAX_S = 1.0;

export interface Playback {
  readonly playing: boolean;
  /** The tick under the playhead while playing, else null. */
  readonly playheadTick: number | null;
  play: (fromTick?: number) => void;
  stop: () => void;
}

interface Running {
  ctx: AudioContext;
  plan: PlaybackPlan;
  startTime: number;
  originMs: number;
  nextClick: number;
  nextNote: number;
  timer: ReturnType<typeof setInterval>;
  frame: number;
}

function blip(ctx: AudioContext, at: number, hz: number, gain: number, length: number, type: OscillatorType): void {
  const osc = ctx.createOscillator();
  const amp = ctx.createGain();
  osc.type = type;
  osc.frequency.value = hz;
  amp.gain.setValueAtTime(0, at);
  amp.gain.linearRampToValueAtTime(gain, at + 0.004);
  amp.gain.setValueAtTime(gain, at + Math.max(0.005, length - 0.02));
  amp.gain.linearRampToValueAtTime(0, at + length);
  osc.connect(amp).connect(ctx.destination);
  osc.start(at);
  osc.stop(at + length + 0.01);
}

export function usePlayback(score: Score | null): Playback {
  const [playing, setPlaying] = useState(false);
  const [playheadTick, setPlayheadTick] = useState<number | null>(null);
  const running = useRef<Running | null>(null);

  const stop = useCallback(() => {
    const r = running.current;
    running.current = null;
    if (r) {
      clearInterval(r.timer);
      cancelAnimationFrame(r.frame);
      r.ctx.close().catch((error: unknown) => console.warn('score playback: closing the audio context failed', { error }));
    }
    setPlaying(false);
    setPlayheadTick(null);
  }, []);

  const play = useCallback(
    (fromTick = 0) => {
      stop();
      if (score === null) return;
      const AudioCtor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!AudioCtor) {
        console.warn('score playback: Web Audio is not available');
        return;
      }
      const ctx = new AudioCtor();
      const plan = playbackPlan(score, fromTick);
      const r: Running = {
        ctx,
        plan,
        startTime: ctx.currentTime + START_DELAY_S,
        originMs: tickToMs(score, fromTick),
        nextClick: 0,
        nextNote: 0,
        timer: setInterval(() => schedule(), TIMER_MS),
        frame: 0,
      };
      const schedule = () => {
        const horizon = ctx.currentTime + LOOKAHEAD_S;
        while (r.nextClick < plan.clicks.length) {
          const click = plan.clicks[r.nextClick];
          const at = r.startTime + click.ms / 1000;
          if (at > horizon) break;
          blip(ctx, at, click.downbeat ? 1600 : 1000, click.downbeat ? 0.32 : 0.16, CLICK_S, 'square');
          r.nextClick++;
        }
        while (r.nextNote < plan.notes.length) {
          const note = plan.notes[r.nextNote];
          const at = r.startTime + note.ms / 1000;
          if (at > horizon) break;
          blip(ctx, at, NOTE_HZ, note.gain * 0.5, Math.min(NOTE_MAX_S, Math.max(0.04, (note.durationMs / 1000) * 0.85)), 'triangle');
          r.nextNote++;
        }
      };
      const tick = () => {
        if (running.current !== r) return;
        const elapsedMs = (ctx.currentTime - r.startTime) * 1000;
        if (elapsedMs >= plan.endMs) {
          stop();
          return;
        }
        setPlayheadTick(elapsedMs < 0 ? fromTick : msToTick(score, r.originMs + elapsedMs));
        r.frame = requestAnimationFrame(tick);
      };
      running.current = r;
      schedule();
      r.frame = requestAnimationFrame(tick);
      setPlaying(true);
      setPlayheadTick(fromTick);
    },
    [score, stop],
  );

  // Stop when the editor closes.
  useEffect(() => stop, [stop]);

  return { playing, playheadTick, play, stop };
}
