import { describe, it, expect } from 'vitest';
import { IDLE_RECORDER, canCancel, lineNumber, reduceRecorder } from './recorder';
import type { RecorderEvent, RecorderState } from './recorder';
import { CaptureError, VoiceError } from './types';
import type { VoiceTakeResponse } from './types';

const blob = new Blob(['abc'], { type: 'audio/webm' });
const result = { created: true } as VoiceTakeResponse;

function run(events: RecorderEvent[], start: RecorderState = IDLE_RECORDER): RecorderState {
  return events.reduce(reduceRecorder, start);
}

const recording = (breaks: number[] = []): RecorderState => ({ kind: 'recording', startedAt: 1000, breaksMs: breaks });

describe('reduceRecorder', () => {
  it('runs the happy path idle -> requesting -> recording -> stopping -> uploading -> done', () => {
    let s = reduceRecorder(IDLE_RECORDER, { type: 'start' });
    expect(s).toEqual({ kind: 'requesting_permission' });
    s = reduceRecorder(s, { type: 'started', at: 1000 });
    expect(s).toEqual(recording());
    s = reduceRecorder(s, { type: 'break', at: 2500 });
    s = reduceRecorder(s, { type: 'break', at: 4000.4 });
    expect(s).toEqual(recording([1500, 3000]));
    s = reduceRecorder(s, { type: 'stop' });
    expect(s).toEqual({ kind: 'stopping', startedAt: 1000, breaksMs: [1500, 3000] });
    s = reduceRecorder(s, { type: 'recorded', blob, mimeType: 'audio/webm', durationMs: 5000 });
    expect(s).toEqual({
      kind: 'uploading',
      attempt: 1,
      take: { blob, mimeType: 'audio/webm', durationMs: 5000, breaksMs: [1500, 3000] },
    });
    s = reduceRecorder(s, { type: 'upload_succeeded', result });
    expect(s).toEqual({ kind: 'done', result });
  });

  it('keeps breaks relative to the recorder start, ascending, and drops ones before the start', () => {
    const s = run([{ type: 'break', at: 900 }, { type: 'break', at: 1200 }, { type: 'break', at: 1100 }], recording());
    expect(s).toEqual(recording([200]));
  });

  it('drops breaks past the recorded duration when the take is finalised', () => {
    const s = run(
      [{ type: 'stop' }, { type: 'recorded', blob, mimeType: 'audio/webm', durationMs: 1000 }],
      recording([500, 1000, 1500]),
    );
    expect(s.kind === 'uploading' && s.take.breaksMs).toEqual([500]);
  });

  it('ignores taps, stops and uploads outside the states they belong to', () => {
    expect(reduceRecorder(IDLE_RECORDER, { type: 'break', at: 5 })).toBe(IDLE_RECORDER);
    const requesting: RecorderState = { kind: 'requesting_permission' };
    expect(reduceRecorder(requesting, { type: 'break', at: 5 })).toBe(requesting);
    expect(reduceRecorder(requesting, { type: 'stop' })).toBe(requesting);
    const up = run([{ type: 'stop' }, { type: 'recorded', blob, mimeType: 'a', durationMs: 1 }], recording());
    expect(reduceRecorder(up, { type: 'break', at: 5000 })).toBe(up);
    expect(reduceRecorder(up, { type: 'start' })).toBe(up);
  });

  it('cancel returns to idle from every state', () => {
    const states: RecorderState[] = [
      IDLE_RECORDER,
      { kind: 'requesting_permission' },
      recording([1]),
      { kind: 'stopping', startedAt: 0, breaksMs: [] },
      { kind: 'uploading', attempt: 1, take: { blob, mimeType: 'a', durationMs: 1, breaksMs: [] } },
      { kind: 'upload_failed', take: { blob, mimeType: 'a', durationMs: 1, breaksMs: [] }, error: new VoiceError('network', 'x'), attempt: 1 },
      { kind: 'capture_failed', error: new CaptureError('permission_denied', 'x') },
    ];
    for (const s of states) expect(reduceRecorder(s, { type: 'cancel' })).toEqual(IDLE_RECORDER);
  });

  it('a capture failure while requesting, recording or stopping lands in capture_failed; retry asks again', () => {
    const error = new CaptureError('permission_denied', 'no');
    const failed = reduceRecorder({ kind: 'requesting_permission' }, { type: 'capture_failed', error });
    expect(failed).toEqual({ kind: 'capture_failed', error });
    expect(reduceRecorder(recording(), { type: 'capture_failed', error }).kind).toBe('capture_failed');
    expect(reduceRecorder({ kind: 'stopping', startedAt: 0, breaksMs: [] }, { type: 'capture_failed', error }).kind).toBe('capture_failed');
    expect(reduceRecorder(failed, { type: 'retry' })).toEqual({ kind: 'requesting_permission' });
    expect(reduceRecorder(failed, { type: 'discard' })).toEqual(IDLE_RECORDER);
  });

  it('an upload failure keeps the take; retry re-uploads the same blob with the next attempt number', () => {
    const error = new VoiceError('stt_unavailable', 'loading', 503);
    const up = run([{ type: 'stop' }, { type: 'recorded', blob, mimeType: 'audio/webm', durationMs: 3000 }], recording([700]));
    const failed = reduceRecorder(up, { type: 'upload_failed', error });
    expect(failed.kind).toBe('upload_failed');
    if (failed.kind !== 'upload_failed' || up.kind !== 'uploading') throw new Error('unreachable');
    expect(failed.take).toBe(up.take);
    expect(failed.error).toBe(error);
    const again = reduceRecorder(failed, { type: 'retry' });
    expect(again).toEqual({ kind: 'uploading', attempt: 2, take: up.take });
    expect(reduceRecorder(failed, { type: 'discard' })).toEqual(IDLE_RECORDER);
    const failedTwice = reduceRecorder(again, { type: 'upload_failed', error });
    expect(reduceRecorder(failedTwice, { type: 'retry' })).toEqual({ kind: 'uploading', attempt: 3, take: up.take });
  });

  it('start is accepted from idle only (an open session never restarts silently)', () => {
    expect(reduceRecorder(recording(), { type: 'start' }).kind).toBe('recording');
  });
});

describe('helpers', () => {
  it('lineNumber counts committed breaks plus one, plus a pending tap', () => {
    expect(lineNumber(recording(), false)).toBe(1);
    expect(lineNumber(recording([1, 2]), false)).toBe(3);
    expect(lineNumber(recording([1, 2]), true)).toBe(4);
    expect(lineNumber(IDLE_RECORDER, true)).toBe(1);
  });

  it('canCancel is false only once the take is done', () => {
    expect(canCancel(IDLE_RECORDER)).toBe(true);
    expect(canCancel(recording())).toBe(true);
    expect(canCancel({ kind: 'done', result })).toBe(false);
  });
});
