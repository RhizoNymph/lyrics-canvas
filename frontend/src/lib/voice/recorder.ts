/**
 * The recording modal's lifecycle as a pure state machine.
 *
 *   idle -start-> requesting_permission -started-> recording -stop-> stopping -recorded-> uploading
 *   uploading -upload_succeeded-> done          uploading -upload_failed-> upload_failed (keeps the take)
 *   requesting/recording/stopping -capture_failed-> capture_failed
 *   upload_failed -retry-> uploading (same take)  capture_failed -retry-> requesting_permission
 *   upload_failed/capture_failed -discard-> idle  any -cancel-> idle
 *
 * Events that do not apply to the current state are ignored (the same state object is returned), so
 * late callbacks (a timer firing after stop, a tap during upload) cannot corrupt a session.
 * `startedAt` and `break.at` are on one monotonic clock; breaks are stored as whole ms since the
 * recorder actually started.
 */
import type { CaptureError, Take, VoiceError, VoiceTakeResponse } from './types';

export type RecorderState =
  | { kind: 'idle' }
  | { kind: 'requesting_permission' }
  | { kind: 'recording'; startedAt: number; breaksMs: readonly number[] }
  /** Stop requested; waiting for the recorder's final data. */
  | { kind: 'stopping'; startedAt: number; breaksMs: readonly number[] }
  | { kind: 'uploading'; take: Take; attempt: number }
  | { kind: 'done'; result: VoiceTakeResponse }
  | { kind: 'capture_failed'; error: CaptureError }
  /** `attempt` is the number of the upload that failed. */
  | { kind: 'upload_failed'; take: Take; error: VoiceError; attempt: number };

export type RecorderKind = RecorderState['kind'];

export const IDLE_RECORDER: RecorderState = { kind: 'idle' };

export type RecorderEvent =
  | { type: 'start' }
  /** The MediaRecorder fired `start` at clock time `at`. */
  | { type: 'started'; at: number }
  /** A committed single tap at clock time `at` (its pointerdown). */
  | { type: 'break'; at: number }
  | { type: 'stop' }
  | { type: 'recorded'; blob: Blob; mimeType: string; durationMs: number }
  | { type: 'capture_failed'; error: CaptureError }
  | { type: 'upload_succeeded'; result: VoiceTakeResponse }
  | { type: 'upload_failed'; error: VoiceError }
  | { type: 'retry' }
  | { type: 'discard' }
  | { type: 'cancel' };

export function reduceRecorder(state: RecorderState, event: RecorderEvent): RecorderState {
  switch (event.type) {
    case 'start':
      return state.kind === 'idle' ? { kind: 'requesting_permission' } : state;
    case 'started':
      return state.kind === 'requesting_permission' ? { kind: 'recording', startedAt: event.at, breaksMs: [] } : state;
    case 'break': {
      if (state.kind !== 'recording') return state;
      const ms = Math.round(event.at - state.startedAt);
      const last = state.breaksMs.length > 0 ? state.breaksMs[state.breaksMs.length - 1] : -1;
      if (ms < 0 || ms <= last) return state;
      return { ...state, breaksMs: [...state.breaksMs, ms] };
    }
    case 'stop':
      return state.kind === 'recording' ? { kind: 'stopping', startedAt: state.startedAt, breaksMs: state.breaksMs } : state;
    case 'recorded': {
      if (state.kind !== 'stopping') return state;
      const durationMs = Math.max(0, Math.round(event.durationMs));
      return {
        kind: 'uploading',
        attempt: 1,
        take: {
          blob: event.blob,
          mimeType: event.mimeType,
          durationMs,
          breaksMs: state.breaksMs.filter((ms) => ms < durationMs),
        },
      };
    }
    case 'capture_failed':
      return state.kind === 'requesting_permission' || state.kind === 'recording' || state.kind === 'stopping'
        ? { kind: 'capture_failed', error: event.error }
        : state;
    case 'upload_succeeded':
      return state.kind === 'uploading' ? { kind: 'done', result: event.result } : state;
    case 'upload_failed':
      return state.kind === 'uploading'
        ? { kind: 'upload_failed', take: state.take, error: event.error, attempt: state.attempt }
        : state;
    case 'retry':
      if (state.kind === 'upload_failed') return { kind: 'uploading', take: state.take, attempt: state.attempt + 1 };
      if (state.kind === 'capture_failed') return { kind: 'requesting_permission' };
      return state;
    case 'discard':
      return state.kind === 'upload_failed' || state.kind === 'capture_failed' ? IDLE_RECORDER : state;
    case 'cancel':
      return IDLE_RECORDER;
  }
}

/** "Line N" while recording: committed breaks + 1, plus one when a tap is pending. */
export function lineNumber(state: RecorderState, tapPending: boolean): number {
  if (state.kind !== 'recording') return 1;
  return state.breaksMs.length + 1 + (tapPending ? 1 : 0);
}

/** Cancel is offered until the take is done. */
export function canCancel(state: RecorderState): boolean {
  return state.kind !== 'done';
}
