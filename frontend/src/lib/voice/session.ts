/**
 * One recording session: drives the pure recorder machine, the tap/double-tap reducer and the silence
 * VAD, and talks to an injected capture device and uploader. No DOM or React here, so the whole flow
 * runs under fake timers in tests; the browser capture lives in `mediaCapture.ts` and the modal
 * subscribes through `subscribe`/`getSnapshot` (useSyncExternalStore).
 *
 * Ordering guarantees:
 * - breaks are committed only while recording, relative to `CaptureHandle.startedAt`;
 * - `beforeUpload` (flush the open editor) runs and resolves before every upload attempt;
 * - after `cancel`/`dispose` no callback of the old attempt changes state or reaches `onDone`, and the
 *   mic is released even when the permission prompt resolves later.
 */
import { captureErrorFromException, toVoiceError } from './errors';
import { IDLE_GESTURE, reduceGesture } from './gesture';
import type { GestureEffect, GestureEvent, GestureState } from './gesture';
import { meterLevel } from './level';
import { IDLE_RECORDER, reduceRecorder } from './recorder';
import type { RecorderEvent, RecorderState } from './recorder';
import type { SilenceSeconds } from './silenceSetting';
import { VoiceError } from './types';
import type { Take, VoiceTakeResponse } from './types';
import { INITIAL_VAD, stepVad } from './vad';
import type { VadState } from './vad';

export interface RecordedAudio {
  blob: Blob;
  mimeType: string;
  durationMs: number;
}

/** A live microphone recording. */
export interface CaptureHandle {
  readonly mimeType: string;
  /** Clock time (same clock as `SessionDeps.now`) at which the recorder actually started. */
  readonly startedAt: number;
  /** Current input RMS in [0, 1]. */
  rms(): number;
  /** Finish and release the mic; resolves with the whole recording. */
  stop(): Promise<RecordedAudio>;
  /** Abandon and release the mic. Idempotent. */
  cancel(): void;
}

export interface SessionDeps {
  /** Ask for the mic and start recording; resolves once recording has started. */
  startCapture: () => Promise<CaptureHandle>;
  /** Runs before each upload attempt (flush the open editor). A rejection fails the attempt. */
  beforeUpload: () => Promise<void>;
  upload: (take: Take, signal: AbortSignal) => Promise<VoiceTakeResponse>;
  onDone: (result: VoiceTakeResponse) => void;
  /** Monotonic ms clock (performance.now in the browser). */
  now: () => number;
  /** Read on every level tick, so changing the setting in the modal applies to the running take. */
  silenceSeconds: () => SilenceSeconds;
  /** Level / VAD / elapsed tick interval. */
  tickMs?: number;
}

export interface SessionSnapshot {
  recorder: RecorderState;
  /** A single tap is waiting out the double-tap window. */
  tapPending: boolean;
  elapsedMs: number;
  /** Input meter, 0..1 (dB scale). */
  level: number;
}

const DEFAULT_TICK_MS = 100;

export class VoiceSession {
  private readonly deps: SessionDeps;
  private snapshot: SessionSnapshot = { recorder: IDLE_RECORDER, tapPending: false, elapsedMs: 0, level: 0 };
  private readonly listeners = new Set<() => void>();
  private gesture: GestureState = IDLE_GESTURE;
  private gestureTimer: ReturnType<typeof setTimeout> | null = null;
  private ticker: ReturnType<typeof setInterval> | null = null;
  private vad: VadState = INITIAL_VAD;
  private handle: CaptureHandle | null = null;
  private abort: AbortController | null = null;
  /** Bumped by cancel/dispose/retry so stale async completions are dropped. */
  private generation = 0;
  private disposed = false;

  constructor(deps: SessionDeps) {
    this.deps = deps;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): SessionSnapshot => this.snapshot;

  /** Ask for the mic and start recording (idle only). */
  start(): void {
    if (this.snapshot.recorder.kind !== 'idle') return;
    this.dispatch({ type: 'start' });
    this.requestCapture();
  }

  /** A tap on the recording surface (pointerdown / Space / Enter). */
  tap(): void {
    if (this.snapshot.recorder.kind !== 'recording') return;
    this.gestureEvent({ type: 'tap', at: this.deps.now() });
  }

  /** The Stop button or silence auto-stop: commit a pending tap, then finish. */
  stop(): void {
    if (this.snapshot.recorder.kind !== 'recording') return;
    this.gestureEvent({ type: 'flush' });
    this.finish();
  }

  retry(): void {
    const kind = this.snapshot.recorder.kind;
    if (kind !== 'upload_failed' && kind !== 'capture_failed') return;
    this.dispatch({ type: 'retry' });
    if (kind === 'upload_failed') void this.uploadCurrent();
    else this.requestCapture();
  }

  discard(): void {
    this.dispatch({ type: 'discard' });
  }

  /** Abandon the session from any state: release the mic, abort an upload, drop the take. */
  cancel(): void {
    this.generation++;
    this.gestureEvent({ type: 'reset' });
    this.stopTicker();
    this.releaseHandle(true);
    this.abort?.abort();
    this.abort = null;
    this.vad = INITIAL_VAD;
    this.dispatch({ type: 'cancel' });
  }

  dispose(): void {
    if (this.disposed) return;
    this.cancel();
    this.disposed = true;
    this.listeners.clear();
  }

  // ---- internals ----

  private requestCapture(): void {
    const gen = ++this.generation;
    this.deps.startCapture().then(
      (handle) => {
        if (gen !== this.generation || this.snapshot.recorder.kind !== 'requesting_permission') {
          handle.cancel();
          return;
        }
        this.handle = handle;
        this.vad = INITIAL_VAD;
        this.dispatch({ type: 'started', at: handle.startedAt });
        this.startTicker();
      },
      (err: unknown) => {
        if (gen !== this.generation) return;
        this.dispatch({ type: 'capture_failed', error: captureErrorFromException(err) });
      },
    );
  }

  private finish(): void {
    if (this.snapshot.recorder.kind !== 'recording') return;
    const handle = this.handle;
    this.stopTicker();
    this.dispatch({ type: 'stop' });
    if (!handle) return;
    const gen = this.generation;
    handle.stop().then(
      (audio) => {
        if (gen !== this.generation) return;
        this.handle = null;
        this.dispatch({ type: 'recorded', blob: audio.blob, mimeType: audio.mimeType, durationMs: audio.durationMs });
        void this.uploadCurrent();
      },
      (err: unknown) => {
        if (gen !== this.generation) return;
        this.releaseHandle(true);
        this.dispatch({ type: 'capture_failed', error: captureErrorFromException(err) });
      },
    );
  }

  private async uploadCurrent(): Promise<void> {
    const state = this.snapshot.recorder;
    if (state.kind !== 'uploading') return;
    const gen = this.generation;
    const abort = new AbortController();
    this.abort = abort;
    try {
      await this.deps.beforeUpload();
    } catch (err) {
      if (gen !== this.generation) return;
      const message = err instanceof Error ? err.message : String(err);
      this.dispatch({ type: 'upload_failed', error: new VoiceError('editor_save_failed', message) });
      return;
    }
    if (gen !== this.generation || abort.signal.aborted) return;
    let result: VoiceTakeResponse;
    try {
      result = await this.deps.upload(state.take, abort.signal);
    } catch (err) {
      if (gen !== this.generation) return;
      this.dispatch({ type: 'upload_failed', error: toVoiceError(err) });
      return;
    }
    if (gen !== this.generation) return;
    this.abort = null;
    this.dispatch({ type: 'upload_succeeded', result });
    this.deps.onDone(result);
  }

  private gestureEvent(event: GestureEvent): void {
    const step = reduceGesture(this.gesture, event);
    this.gesture = step.state;
    const pending = step.state.kind === 'pending';
    if (pending !== this.snapshot.tapPending) this.update({ tapPending: pending });
    for (const effect of step.effects) this.applyGestureEffect(effect);
  }

  private applyGestureEffect(effect: GestureEffect): void {
    switch (effect.kind) {
      case 'arm_timer':
        this.clearGestureTimer();
        this.gestureTimer = setTimeout(() => {
          this.gestureTimer = null;
          this.gestureEvent({ type: 'window_elapsed', at: this.deps.now() });
        }, effect.delayMs);
        break;
      case 'cancel_timer':
        this.clearGestureTimer();
        break;
      case 'commit_break':
        this.dispatch({ type: 'break', at: effect.at });
        break;
      case 'stop':
        this.finish();
        break;
    }
  }

  private clearGestureTimer(): void {
    if (this.gestureTimer !== null) clearTimeout(this.gestureTimer);
    this.gestureTimer = null;
  }

  private startTicker(): void {
    this.stopTicker();
    this.ticker = setInterval(() => this.tick(), this.deps.tickMs ?? DEFAULT_TICK_MS);
  }

  private stopTicker(): void {
    if (this.ticker !== null) clearInterval(this.ticker);
    this.ticker = null;
  }

  private tick(): void {
    const state = this.snapshot.recorder;
    const handle = this.handle;
    if (state.kind !== 'recording' || !handle) return;
    const at = this.deps.now();
    const rms = handle.rms();
    const step = stepVad(this.vad, { rms, at }, this.deps.silenceSeconds() * 1000);
    this.vad = step.state;
    this.update({ elapsedMs: Math.max(0, at - state.startedAt), level: meterLevel(rms) });
    if (step.stop) this.stop();
  }

  private releaseHandle(cancel: boolean): void {
    if (cancel) this.handle?.cancel();
    this.handle = null;
  }

  private dispatch(event: RecorderEvent): void {
    const next = reduceRecorder(this.snapshot.recorder, event);
    if (next === this.snapshot.recorder) return;
    const resetMeters = next.kind !== 'recording';
    this.update(resetMeters ? { recorder: next, level: 0 } : { recorder: next });
  }

  private update(patch: Partial<SessionSnapshot>): void {
    if (this.disposed) return;
    this.snapshot = { ...this.snapshot, ...patch };
    for (const listener of this.listeners) listener();
  }
}
