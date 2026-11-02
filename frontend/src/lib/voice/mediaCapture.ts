/**
 * Browser capture adapter: getUserMedia + MediaRecorder for the audio, a Web Audio AnalyserNode for the
 * live RMS level. Thin by design; decisions (mime preference, error mapping, VAD) live in pure modules.
 * The browser constructors are injected so tests can run it against fakes.
 *
 * Invariant: every path out (stop, cancel, any failure) stops all mic tracks and closes the
 * AudioContext exactly once.
 */
import { captureErrorFromException } from './errors';
import { rmsOf } from './level';
import type { CaptureHandle, RecordedAudio } from './session';
import { pickMimeType, recordingSupport } from './support';
import { CaptureError } from './types';

/** The parts of MediaRecorder this adapter uses. */
export interface RecorderLike {
  readonly mimeType: string;
  readonly state: 'inactive' | 'recording' | 'paused';
  ondataavailable: ((event: { data: Blob }) => void) | null;
  onstart: (() => void) | null;
  onstop: (() => void) | null;
  onerror: ((event: unknown) => void) | null;
  start(): void;
  stop(): void;
}

export interface RecorderCtor {
  new (stream: MediaStream, options?: { mimeType?: string }): RecorderLike;
  isTypeSupported(mime: string): boolean;
}

/** The parts of AudioContext / AnalyserNode this adapter uses. */
export interface AnalyserLike {
  fftSize: number;
  getFloatTimeDomainData(array: Float32Array): void;
}

export interface AudioContextLike {
  createMediaStreamSource(stream: MediaStream): { connect(node: AnalyserLike): unknown };
  createAnalyser(): AnalyserLike;
  resume(): Promise<void>;
  close(): Promise<void>;
}

export interface CaptureEnvironment {
  isSecureContext: boolean;
  getUserMedia: ((constraints: MediaStreamConstraints) => Promise<MediaStream>) | null;
  MediaRecorder: RecorderCtor | null;
  /** Null when Web Audio is unavailable: recording still works, the meter and silence stop do not. */
  createAudioContext: (() => AudioContextLike) | null;
  now: () => number;
}

export function browserCaptureEnvironment(): CaptureEnvironment {
  const g = globalThis as unknown as {
    isSecureContext?: boolean;
    navigator?: { mediaDevices?: MediaDevices };
    MediaRecorder?: RecorderCtor;
    AudioContext?: new () => AudioContextLike;
    webkitAudioContext?: new () => AudioContextLike;
    performance: { now(): number };
  };
  const devices = g.navigator?.mediaDevices;
  const AudioContextCtor = g.AudioContext ?? g.webkitAudioContext ?? null;
  return {
    isSecureContext: g.isSecureContext === true,
    getUserMedia: devices && typeof devices.getUserMedia === 'function' ? (c) => devices.getUserMedia(c) : null,
    MediaRecorder: typeof g.MediaRecorder === 'function' ? g.MediaRecorder : null,
    createAudioContext: AudioContextCtor ? () => new AudioContextCtor() : null,
    now: () => g.performance.now(),
  };
}

function levelMeter(env: CaptureEnvironment, stream: MediaStream): { rms: () => number; close: () => void } {
  const silent = { rms: () => 0, close: () => undefined };
  if (!env.createAudioContext) return silent;
  let ctx: AudioContextLike;
  try {
    ctx = env.createAudioContext();
  } catch (error) {
    console.warn('voice capture: AudioContext unavailable; level meter off', { error });
    return silent;
  }
  try {
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 2048;
    ctx.createMediaStreamSource(stream).connect(analyser);
    // Contexts created after an await may start suspended (autoplay policy).
    ctx.resume().catch((error: unknown) => console.warn('voice capture: AudioContext resume failed', { error }));
    const buffer = new Float32Array(analyser.fftSize);
    return {
      rms: () => {
        analyser.getFloatTimeDomainData(buffer);
        return rmsOf(buffer);
      },
      close: () => {
        ctx.close().catch((error: unknown) => console.warn('voice capture: AudioContext close failed', { error }));
      },
    };
  } catch (error) {
    console.warn('voice capture: analyser setup failed; level meter off', { error });
    ctx.close().catch(() => undefined);
    return silent;
  }
}

/** Ask for the mic and start recording; resolves once the recorder fired `start`. */
export async function startMediaCapture(env: CaptureEnvironment = browserCaptureEnvironment()): Promise<CaptureHandle> {
  const support = recordingSupport({
    isSecureContext: env.isSecureContext,
    hasMediaDevices: env.getUserMedia !== null,
    hasMediaRecorder: env.MediaRecorder !== null,
  });
  if (!support.ok) throw new CaptureError(support.reason, support.message);
  const Recorder = env.MediaRecorder as RecorderCtor;
  const getUserMedia = env.getUserMedia as (c: MediaStreamConstraints) => Promise<MediaStream>;

  let stream: MediaStream;
  try {
    stream = await getUserMedia({ audio: true });
  } catch (err) {
    throw captureErrorFromException(err);
  }

  let released = false;
  const meter = levelMeter(env, stream);
  const release = () => {
    if (released) return;
    released = true;
    for (const track of stream.getTracks()) track.stop();
    meter.close();
  };

  let recorder: RecorderLike;
  const preferred = pickMimeType((m) => Recorder.isTypeSupported(m));
  try {
    recorder = preferred ? new Recorder(stream, { mimeType: preferred }) : new Recorder(stream);
  } catch (err) {
    release();
    throw new CaptureError('unsupported', err instanceof Error ? err.message : String(err));
  }

  const chunks: Blob[] = [];
  recorder.ondataavailable = (event) => {
    if (event.data.size > 0) chunks.push(event.data);
  };

  let startedAt: number;
  try {
    startedAt = await new Promise<number>((resolve, reject) => {
      recorder.onstart = () => resolve(env.now());
      recorder.onerror = (event) => reject(event);
      recorder.start();
    });
  } catch (err) {
    release();
    throw new CaptureError('recorder_failed', err instanceof Error ? err.message : 'The recorder failed to start');
  }

  const stopRecorder = () => {
    if (recorder.state === 'inactive') return;
    try {
      recorder.stop();
    } catch (error) {
      console.warn('voice capture: recorder.stop threw', { error });
    }
  };

  return {
    mimeType: recorder.mimeType || preferred || '',
    startedAt,
    rms: () => (released ? 0 : meter.rms()),
    stop: () =>
      new Promise<RecordedAudio>((resolve, reject) => {
        if (released) {
          reject(new CaptureError('recorder_failed', 'The recording was already released'));
          return;
        }
        recorder.onstop = () => {
          const durationMs = env.now() - startedAt;
          release();
          const mimeType = recorder.mimeType || preferred || chunks[0]?.type || 'audio/webm';
          const blob = new Blob(chunks, { type: mimeType });
          if (blob.size === 0) reject(new CaptureError('recorder_failed', 'The recording is empty'));
          else resolve({ blob, mimeType, durationMs });
        };
        recorder.onerror = (event) => {
          release();
          reject(new CaptureError('recorder_failed', event instanceof Error ? event.message : 'The recorder failed'));
        };
        stopRecorder();
      }),
    cancel: () => {
      recorder.ondataavailable = null;
      recorder.onstop = null;
      recorder.onerror = null;
      stopRecorder();
      release();
    },
  };
}
