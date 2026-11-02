import { describe, it, expect, vi } from 'vitest';
import { startMediaCapture } from './mediaCapture';
import type { AudioContextLike, CaptureEnvironment, RecorderCtor, RecorderLike } from './mediaCapture';
import { CaptureError } from './types';

interface World {
  env: CaptureEnvironment;
  tracksStopped: () => number;
  contextsClosed: () => number;
  recorders: FakeRecorder[];
  clock: { t: number };
}

class FakeRecorder implements RecorderLike {
  static supported: string[] = ['audio/webm;codecs=opus', 'audio/mp4'];
  static instances: FakeRecorder[] = [];
  static failStart = false;
  static isTypeSupported(mime: string) {
    return FakeRecorder.supported.includes(mime);
  }
  mimeType: string;
  state: 'inactive' | 'recording' | 'paused' = 'inactive';
  ondataavailable: ((event: { data: Blob }) => void) | null = null;
  onstart: (() => void) | null = null;
  onstop: (() => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  emitOnStop: Blob = new Blob(['opus-bytes'], { type: 'audio/webm;codecs=opus' });
  constructor(_stream: MediaStream, options?: { mimeType?: string }) {
    this.mimeType = options?.mimeType ?? 'audio/webm';
    FakeRecorder.instances.push(this);
  }
  start() {
    if (FakeRecorder.failStart) {
      queueMicrotask(() => this.onerror?.(new Error('start failed')));
      return;
    }
    this.state = 'recording';
    queueMicrotask(() => this.onstart?.());
  }
  stop() {
    this.state = 'inactive';
    queueMicrotask(() => {
      this.ondataavailable?.({ data: this.emitOnStop });
      this.onstop?.();
    });
  }
}

function world(overrides: Partial<CaptureEnvironment> = {}): World {
  let stopped = 0;
  let closed = 0;
  const clock = { t: 5000 };
  FakeRecorder.instances = [];
  FakeRecorder.failStart = false;
  FakeRecorder.supported = ['audio/webm;codecs=opus', 'audio/mp4'];
  const stream = {
    getTracks: () => [
      {
        stop: () => {
          stopped++;
        },
      },
    ],
  } as unknown as MediaStream;
  const ctx: AudioContextLike = {
    createAnalyser: () => ({
      fftSize: 0,
      getFloatTimeDomainData: (a: Float32Array) => a.fill(0.5),
    }),
    createMediaStreamSource: () => ({ connect: () => undefined }),
    resume: async () => undefined,
    close: async () => {
      closed++;
    },
  };
  const env: CaptureEnvironment = {
    isSecureContext: true,
    getUserMedia: async () => stream,
    MediaRecorder: FakeRecorder as unknown as RecorderCtor,
    createAudioContext: () => ctx,
    now: () => clock.t,
    ...overrides,
  };
  return { env, tracksStopped: () => stopped, contextsClosed: () => closed, recorders: FakeRecorder.instances, clock };
}

describe('startMediaCapture', () => {
  it('prefers webm/opus, reports the start time from onstart and the analyser RMS', async () => {
    const w = world();
    const handle = await startMediaCapture(w.env);
    expect(w.recorders[0].mimeType).toBe('audio/webm;codecs=opus');
    expect(handle.mimeType).toBe('audio/webm;codecs=opus');
    expect(handle.startedAt).toBe(5000);
    expect(handle.rms()).toBeCloseTo(0.5);
    handle.cancel();
  });

  it('falls back to audio/mp4 (Safari)', async () => {
    const w = world();
    FakeRecorder.supported = ['audio/mp4'];
    const handle = await startMediaCapture(w.env);
    expect(w.recorders[0].mimeType).toBe('audio/mp4');
    handle.cancel();
  });

  it('stop resolves the whole recording with its duration and releases the mic once', async () => {
    const w = world();
    const handle = await startMediaCapture(w.env);
    w.clock.t = 8250;
    const audio = await handle.stop();
    expect(audio.durationMs).toBe(3250);
    expect(audio.mimeType).toBe('audio/webm;codecs=opus');
    expect(audio.blob.size).toBeGreaterThan(0);
    expect(w.tracksStopped()).toBe(1);
    expect(w.contextsClosed()).toBe(1);
    handle.cancel();
    expect(w.tracksStopped()).toBe(1);
    expect(handle.rms()).toBe(0);
  });

  it('cancel stops the recorder and releases the mic', async () => {
    const w = world();
    const handle = await startMediaCapture(w.env);
    handle.cancel();
    expect(w.recorders[0].state).toBe('inactive');
    expect(w.tracksStopped()).toBe(1);
    expect(w.contextsClosed()).toBe(1);
  });

  it('rejects an empty recording', async () => {
    const w = world();
    const handle = await startMediaCapture(w.env);
    w.recorders[0].emitOnStop = new Blob([]);
    await expect(handle.stop()).rejects.toMatchObject({ kind: 'recorder_failed' });
    expect(w.tracksStopped()).toBe(1);
  });

  it('refuses without a secure context or MediaRecorder, before touching the mic', async () => {
    const getUserMedia = vi.fn();
    await expect(startMediaCapture(world({ isSecureContext: false, getUserMedia }).env)).rejects.toMatchObject({
      kind: 'insecure_context',
    });
    await expect(startMediaCapture(world({ MediaRecorder: null, getUserMedia }).env)).rejects.toMatchObject({ kind: 'unsupported' });
    expect(getUserMedia).not.toHaveBeenCalled();
  });

  it('maps a denied permission and releases on a recorder start failure', async () => {
    const denied = world({
      getUserMedia: async () => {
        throw Object.assign(new Error('denied'), { name: 'NotAllowedError' });
      },
    });
    const err = await startMediaCapture(denied.env).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(CaptureError);
    expect((err as CaptureError).kind).toBe('permission_denied');

    const w = world();
    FakeRecorder.failStart = true;
    await expect(startMediaCapture(w.env)).rejects.toMatchObject({ kind: 'recorder_failed' });
    expect(w.tracksStopped()).toBe(1);
    expect(w.contextsClosed()).toBe(1);
  });

  it('records without Web Audio (meter reads 0)', async () => {
    const w = world({ createAudioContext: null });
    const handle = await startMediaCapture(w.env);
    expect(handle.rms()).toBe(0);
    handle.cancel();
    expect(w.tracksStopped()).toBe(1);
  });
});
