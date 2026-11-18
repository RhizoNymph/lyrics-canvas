/**
 * Playback plan: when each click and each note sounds (ms from the play start), from the tempo map
 * (tickToMs). Pure; the Web Audio scheduling lives in components/score/usePlayback.ts.
 */
import type { Score } from '@/types/score';
import { barStartTick, tickToMs } from './timing';
import { beatTicks, staffBars } from './layout';
import { barCount } from './timing';

/** Note gain by accent (0 none, 1 accent, 2 strong). */
export const ACCENT_GAIN: readonly [number, number, number] = [0.35, 0.6, 0.9];

export interface PlannedClick {
  readonly ms: number;
  /** A bar downbeat (louder, higher). */
  readonly downbeat: boolean;
}

export interface PlannedNote {
  readonly ms: number;
  readonly durationMs: number;
  readonly gain: number;
  readonly line: number;
  readonly s: number;
}

export interface PlaybackPlan {
  readonly clicks: readonly PlannedClick[];
  readonly notes: readonly PlannedNote[];
  /** ms (from `fromTick`) when playback ends: the end of the last bar holding a note. */
  readonly endMs: number;
  readonly fromTick: number;
  readonly toTick: number;
}

/**
 * Clicks on every beat (downbeats marked) and one event per note in `[fromTick, toTick)`. `toTick`
 * defaults to the end of the last bar holding a note.
 */
export function playbackPlan(score: Score, fromTick = 0, toTick?: number): PlaybackPlan {
  const bars = barCount(score);
  const end = toTick ?? barStartTick(score, bars);
  const origin = tickToMs(score, fromTick);
  const clicks: PlannedClick[] = [];
  for (const bar of staffBars(score, bars)) {
    if (bar.start >= end) break;
    for (const beat of beatTicks(bar)) {
      if (beat.weight < 2 || beat.tick < fromTick || beat.tick >= end) continue;
      clicks.push({ ms: tickToMs(score, beat.tick) - origin, downbeat: beat.weight === 3 });
    }
  }
  const notes: PlannedNote[] = [];
  score.lines.forEach((line, li) => {
    for (const note of line.notes) {
      if (note.t < fromTick || note.t >= end) continue;
      const ms = tickToMs(score, note.t) - origin;
      notes.push({ ms, durationMs: tickToMs(score, note.t + note.d) - origin - ms, gain: ACCENT_GAIN[note.a], line: li, s: note.s });
    }
  });
  return { clicks, notes, endMs: tickToMs(score, end) - origin, fromTick, toTick: end };
}

/** The (fractional) tick sounding `ms` after tick 0: the inverse of tickToMs, for the playhead. */
export function msToTick(score: Score, ms: number): number {
  if (ms <= 0) return 0;
  const starts = [0, ...score.tempos.slice(1).map((tempo) => barStartTick(score, tempo.bar))];
  let elapsed = 0;
  for (let i = 0; i < score.tempos.length; i++) {
    const msPerTick = 60000 / (score.tempos[i].bpm * 48);
    const span = i + 1 < starts.length ? (starts[i + 1] - starts[i]) * msPerTick : Infinity;
    if (ms <= elapsed + span) return starts[i] + (ms - elapsed) / msPerTick;
    elapsed += span;
  }
  return 0;
}
