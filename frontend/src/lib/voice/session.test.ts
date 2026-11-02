import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { VoiceSession } from './session';
import type { CaptureHandle, RecordedAudio, SessionDeps } from './session';
import { CaptureError, VoiceError } from './types';
import type { Take, VoiceTakeResponse } from './types';
import type { SilenceSeconds } from './silenceSetting';

const result = { created: true, snippet: { id: 's-new' } } as VoiceTakeResponse;

interface FakeCapture {
  handle: CaptureHandle;
  rms: { value: number };
  stopped: number;
  cancelled: number;
  stopResult: { resolve: (a: RecordedAudio) => void; reject: (e: unknown) => void } | null;
}

function fakeCapture(startedAt: number, autoResolveStop = true): FakeCapture {
  const fake: FakeCapture = {
    rms: { value: 0 },
    stopped: 0,
    cancelled: 0,
    stopResult: null,
    handle: null as unknown as CaptureHandle,
  };
  fake.handle = {
    mimeType: 'audio/webm;codecs=opus',
    startedAt,
    rms: () => fake.rms.value,
    stop: () => {
      fake.stopped++;
      if (autoResolveStop) {
        return Promise.resolve({
          blob: new Blob(['audio'], { type: 'audio/webm;codecs=opus' }),
          mimeType: 'audio/webm;codecs=opus',
          durationMs: Date.now() - startedAt,
        });
      }
      return new Promise<RecordedAudio>((resolve, reject) => {
        fake.stopResult = { resolve, reject };
      });
    },
    cancel: () => {
      fake.cancelled++;
    },
  };
  return fake;
}

function setup(overrides: Partial<SessionDeps> = {}, silence: SilenceSeconds = 0) {
  const capture = fakeCapture(Date.now());
  const uploads: Array<{ take: Take; signal: AbortSignal }> = [];
  const order: string[] = [];
  let silenceSeconds: SilenceSeconds = silence;
  const deps: SessionDeps = {
    startCapture: vi.fn(async () => {
      order.push('capture');
      return capture.handle;
    }),
    beforeUpload: vi.fn(async () => {
      order.push('flush');
    }),
    upload: vi.fn(async (take: Take, signal: AbortSignal) => {
      order.push('upload');
      uploads.push({ take, signal });
      return result;
    }),
    onDone: vi.fn((r: VoiceTakeResponse) => {
      order.push(`done:${r.snippet.id}`);
    }),
    now: () => Date.now(),
    silenceSeconds: () => silenceSeconds,
    ...overrides,
  };
  const session = new VoiceSession(deps);
  return {
    session,
    deps,
    capture,
    uploads,
    order,
    setSilence: (s: SilenceSeconds) => {
      silenceSeconds = s;
    },
  };
}

/** Let resolved promises run (fake timers do not flush microtasks by themselves). */
async function settle() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

describe('VoiceSession', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(10_000);
  });
  afterEach(() => vi.useRealTimers());

  it('records, commits single taps as breaks since the recorder start, and stops on a double tap', async () => {
    const { session, capture, uploads, order } = setup();
    session.start();
    expect(session.getSnapshot().recorder.kind).toBe('requesting_permission');
    await settle();
    expect(session.getSnapshot().recorder.kind).toBe('recording');

    vi.advanceTimersByTime(1000);
    session.tap(); // t = +1000
    expect(session.getSnapshot().tapPending).toBe(true);
    vi.advanceTimersByTime(300);
    expect(session.getSnapshot().tapPending).toBe(false);
    const rec = session.getSnapshot().recorder;
    expect(rec.kind === 'recording' && rec.breaksMs).toEqual([1000]);

    vi.advanceTimersByTime(1700);
    session.tap(); // +3000 pending
    vi.advanceTimersByTime(120);
    session.tap(); // double tap -> stop, +3000 dropped
    await settle();

    expect(capture.stopped).toBe(1);
    expect(uploads).toHaveLength(1);
    expect(uploads[0].take.breaksMs).toEqual([1000]);
    expect(uploads[0].take.durationMs).toBe(3120);
    expect(order).toEqual(['capture', 'flush', 'upload', 'done:s-new']);
    expect(session.getSnapshot().recorder.kind).toBe('done');
  });

  it('Stop commits a pending tap (the button is not a tap)', async () => {
    const { session, uploads } = setup();
    session.start();
    await settle();
    vi.advanceTimersByTime(500);
    session.tap();
    vi.advanceTimersByTime(100);
    session.stop();
    await settle();
    expect(uploads[0].take.breaksMs).toEqual([500]);
  });

  it('ignores taps before the recorder started', async () => {
    const { session, uploads } = setup();
    session.start();
    session.tap();
    await settle();
    vi.advanceTimersByTime(400);
    session.stop();
    await settle();
    expect(uploads[0].take.breaksMs).toEqual([]);
  });

  it('cancel while recording releases the mic, drops everything and never uploads', async () => {
    const { session, capture, deps } = setup();
    session.start();
    await settle();
    session.tap();
    session.cancel();
    vi.advanceTimersByTime(1000);
    await settle();
    expect(capture.cancelled).toBe(1);
    expect(capture.stopped).toBe(0);
    expect(deps.upload).not.toHaveBeenCalled();
    expect(session.getSnapshot().recorder.kind).toBe('idle');
  });

  it('cancel during the permission prompt releases the late-arriving handle', async () => {
    let resolveCapture: (h: CaptureHandle) => void = () => undefined;
    const capture = fakeCapture(10_000);
    const { session } = setup({
      startCapture: () =>
        new Promise<CaptureHandle>((resolve) => {
          resolveCapture = resolve;
        }),
    });
    session.start();
    session.cancel();
    resolveCapture(capture.handle);
    await settle();
    expect(capture.cancelled).toBe(1);
    expect(session.getSnapshot().recorder.kind).toBe('idle');
  });

  it('cancel during upload aborts the request and does not apply the result', async () => {
    let resolveUpload: (r: VoiceTakeResponse) => void = () => undefined;
    const seen: AbortSignal[] = [];
    const { session, deps } = setup({
      upload: (_take, signal) => {
        seen.push(signal);
        return new Promise<VoiceTakeResponse>((resolve) => {
          resolveUpload = resolve;
        });
      },
    });
    session.start();
    await settle();
    session.stop();
    await settle();
    expect(session.getSnapshot().recorder.kind).toBe('uploading');
    session.cancel();
    expect(seen[0].aborted).toBe(true);
    resolveUpload(result);
    await settle();
    expect(deps.onDone).not.toHaveBeenCalled();
    expect(session.getSnapshot().recorder.kind).toBe('idle');
  });

  it('maps a permission failure to capture_failed; retry asks for the mic again', async () => {
    let calls = 0;
    const capture = fakeCapture(10_000);
    const { session } = setup({
      startCapture: async () => {
        calls++;
        if (calls === 1) throw Object.assign(new Error('denied'), { name: 'NotAllowedError' });
        return capture.handle;
      },
    });
    session.start();
    await settle();
    const failed = session.getSnapshot().recorder;
    expect(failed.kind).toBe('capture_failed');
    expect(failed.kind === 'capture_failed' && failed.error.kind).toBe('permission_denied');
    session.retry();
    await settle();
    expect(session.getSnapshot().recorder.kind).toBe('recording');
  });

  it('an upload failure keeps the blob; retry re-sends the very same take', async () => {
    let attempt = 0;
    const takes: Take[] = [];
    const { session, deps } = setup({
      upload: async (take) => {
        takes.push(take);
        attempt++;
        if (attempt === 1) throw new VoiceError('stt_unavailable', 'loading', 503);
        return result;
      },
    });
    session.start();
    await settle();
    vi.advanceTimersByTime(800);
    session.stop();
    await settle();
    const failed = session.getSnapshot().recorder;
    expect(failed.kind).toBe('upload_failed');
    expect(failed.kind === 'upload_failed' && failed.error.kind).toBe('stt_unavailable');
    session.retry();
    await settle();
    expect(takes).toHaveLength(2);
    expect(takes[1]).toBe(takes[0]);
    expect(deps.beforeUpload).toHaveBeenCalledTimes(2);
    expect(session.getSnapshot().recorder.kind).toBe('done');
  });

  it('a failing editor flush is an editor_save_failed upload failure and nothing is uploaded', async () => {
    const { session, deps } = setup({
      beforeUpload: async () => {
        throw new Error('PUT failed');
      },
    });
    session.start();
    await settle();
    session.stop();
    await settle();
    const s = session.getSnapshot().recorder;
    expect(s.kind === 'upload_failed' && s.error.kind).toBe('editor_save_failed');
    expect(deps.upload).not.toHaveBeenCalled();
  });

  it('discard after a failure returns to idle', async () => {
    const { session } = setup({
      upload: async () => {
        throw new VoiceError('empty_transcript', 'none', 422);
      },
    });
    session.start();
    await settle();
    session.stop();
    await settle();
    session.discard();
    expect(session.getSnapshot().recorder.kind).toBe('idle');
  });

  it('a recorder error while stopping is a capture failure', async () => {
    const capture = fakeCapture(10_000, false);
    const { session } = setup({ startCapture: async () => capture.handle });
    session.start();
    await settle();
    session.stop();
    capture.stopResult!.reject(new CaptureError('recorder_failed', 'boom'));
    await settle();
    expect(session.getSnapshot().recorder.kind).toBe('capture_failed');
  });

  it('auto-stops after N seconds of silence once speech was heard, and reads the setting live', async () => {
    const { session, capture, uploads, setSilence } = setup({}, 0);
    session.start();
    await settle();
    capture.rms.value = 0.004;
    vi.advanceTimersByTime(1000);
    capture.rms.value = 0.2;
    vi.advanceTimersByTime(1000);
    capture.rms.value = 0.004;
    vi.advanceTimersByTime(5000);
    expect(session.getSnapshot().recorder.kind).toBe('recording'); // off
    setSilence(2);
    vi.advanceTimersByTime(100);
    await settle();
    // The silence started 5 s ago, so the next tick with N = 2 stops.
    expect(capture.stopped).toBe(1);
    expect(uploads).toHaveLength(1);
  });

  it('reports elapsed time and a meter level while recording', async () => {
    const { session, capture } = setup();
    session.start();
    await settle();
    capture.rms.value = 1;
    vi.advanceTimersByTime(1500);
    const snap = session.getSnapshot();
    expect(snap.elapsedMs).toBe(1500);
    expect(snap.level).toBe(1);
  });

  it('notifies subscribers and stops after dispose', async () => {
    const { session, capture } = setup();
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);
    session.start();
    expect(listener).toHaveBeenCalled();
    await settle();
    unsubscribe();
    session.dispose();
    expect(capture.cancelled).toBe(1);
    const calls = listener.mock.calls.length;
    vi.advanceTimersByTime(1000);
    expect(listener.mock.calls.length).toBe(calls);
  });

  it('snapshots are stable between changes (useSyncExternalStore contract)', async () => {
    const { session } = setup();
    expect(session.getSnapshot()).toBe(session.getSnapshot());
    session.start();
    const a = session.getSnapshot();
    expect(session.getSnapshot()).toBe(a);
  });
});
