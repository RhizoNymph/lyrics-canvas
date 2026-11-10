import { useCallback, useEffect, useRef, useState } from 'react';
import { voiceApi } from '@/api/client';
import { describeVoiceError, toVoiceError } from '@/lib/voice/errors';
import { clampSeekMs } from '@/lib/voice/timing';

/** The check view's player. `durationMs` falls back to the server's value (see below). */
export type PlayerState =
  | { kind: 'loading' }
  | { kind: 'ready'; playing: boolean; positionMs: number; durationMs: number }
  | { kind: 'failed'; message: string };

export interface RecordingPlayer {
  state: PlayerState;
  toggle: () => void;
  /** Jump to `ms` (clamped); keeps playing or paused as it was. */
  seek: (ms: number) => void;
  /** Jump to `ms` and play. */
  playFrom: (ms: number) => void;
  /** Move by `deltaMs` (clamped). */
  seekBy: (deltaMs: number) => void;
}

/**
 * One recording's audio for the check view: fetched with the bearer token from the existing audio
 * endpoint into a blob URL, played by an `<audio>` element the hook owns. While playing, the position
 * is read every animation frame (timeupdate alone fires about 4 times a second, too coarse for the
 * word highlight). The element, the frame loop and the URL are released on unmount.
 *
 * MediaRecorder WebM has no duration in its header, so `audio.duration` is often Infinity; the
 * recording's `duration_ms` is used then, so the scrubber still has a range.
 */
export function useRecordingPlayer(recordingId: string, fallbackDurationMs: number): RecordingPlayer {
  const [state, setState] = useState<PlayerState>({ kind: 'loading' });
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const durationRef = useRef(fallbackDurationMs);

  useEffect(() => {
    const abort = new AbortController();
    let url: string | null = null;
    let frame: number | null = null;
    let disposed = false;
    const el = new Audio();
    el.preload = 'auto';

    const snapshot = () => {
      if (disposed) return;
      setState({ kind: 'ready', playing: !el.paused && !el.ended, positionMs: el.currentTime * 1000, durationMs: durationRef.current });
    };
    const stopLoop = () => {
      if (frame !== null) cancelAnimationFrame(frame);
      frame = null;
    };
    const loop = () => {
      snapshot();
      frame = el.paused ? null : requestAnimationFrame(loop);
    };
    el.onloadedmetadata = () => {
      const d = el.duration;
      durationRef.current = Number.isFinite(d) && d > 0 ? d * 1000 : fallbackDurationMs;
      snapshot();
    };
    el.onplay = () => {
      stopLoop();
      loop();
    };
    el.onpause = () => {
      stopLoop();
      snapshot();
    };
    el.onended = el.onpause;
    el.onseeked = snapshot;
    el.onerror = () => {
      if (disposed) return;
      console.warn('recordings: check player media error', { recordingId, code: el.error?.code ?? null });
      setState({ kind: 'failed', message: 'This audio cannot be played in this browser' });
    };

    voiceApi.fetchAudio(recordingId, abort.signal).then(
      (blob) => {
        if (disposed) return;
        url = URL.createObjectURL(blob);
        el.src = url;
        audioRef.current = el;
        setState({ kind: 'ready', playing: false, positionMs: 0, durationMs: durationRef.current });
      },
      (err: unknown) => {
        const error = toVoiceError(err);
        if (disposed || error.kind === 'aborted') return;
        console.warn('recordings: check audio fetch failed', { recordingId, kind: error.kind, status: error.status });
        setState({ kind: 'failed', message: `Audio unavailable: ${describeVoiceError(error).title}` });
      },
    );

    return () => {
      disposed = true;
      abort.abort();
      stopLoop();
      el.onplay = el.onpause = el.onended = el.onseeked = el.onerror = el.onloadedmetadata = null;
      el.pause();
      el.removeAttribute('src');
      el.load();
      audioRef.current = null;
      if (url) URL.revokeObjectURL(url);
    };
  }, [recordingId, fallbackDurationMs]);

  const play = useCallback((el: HTMLAudioElement) => {
    el.play().catch((err: unknown) => {
      console.warn('recordings: check playback failed', { recordingId, error: err });
      setState({ kind: 'failed', message: 'Playback failed' });
    });
  }, [recordingId]);

  const toggle = useCallback(() => {
    const el = audioRef.current;
    if (!el) return;
    if (el.paused || el.ended) play(el);
    else el.pause();
  }, [play]);

  const seek = useCallback((ms: number) => {
    const el = audioRef.current;
    if (!el) return;
    el.currentTime = clampSeekMs(ms, 0, durationRef.current) / 1000;
  }, []);

  const playFrom = useCallback(
    (ms: number) => {
      const el = audioRef.current;
      if (!el) return;
      el.currentTime = clampSeekMs(ms, 0, durationRef.current) / 1000;
      if (el.paused) play(el);
    },
    [play],
  );

  const seekBy = useCallback((deltaMs: number) => {
    const el = audioRef.current;
    if (!el) return;
    el.currentTime = clampSeekMs(el.currentTime * 1000, deltaMs, durationRef.current) / 1000;
  }, []);

  return { state, toggle, seek, playFrom, seekBy };
}
