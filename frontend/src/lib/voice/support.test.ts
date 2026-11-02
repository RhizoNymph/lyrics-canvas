import { describe, it, expect } from 'vitest';
import { HTTPS_REQUIRED_MESSAGE, pickMimeType, recordingSupport, uploadFileName } from './support';

describe('recordingSupport', () => {
  const ok = { isSecureContext: true, hasMediaDevices: true, hasMediaRecorder: true };

  it('is ok with a secure context, mediaDevices and MediaRecorder', () => {
    expect(recordingSupport(ok)).toEqual({ ok: true });
  });

  it('needs HTTPS when the context is insecure or mediaDevices is missing', () => {
    expect(recordingSupport({ ...ok, isSecureContext: false })).toEqual({
      ok: false,
      reason: 'insecure_context',
      message: HTTPS_REQUIRED_MESSAGE,
    });
    expect(recordingSupport({ ...ok, hasMediaDevices: false })).toMatchObject({ ok: false, message: 'Recording needs HTTPS' });
  });

  it('is unsupported without MediaRecorder', () => {
    expect(recordingSupport({ ...ok, hasMediaRecorder: false })).toMatchObject({ ok: false, reason: 'unsupported' });
  });
});

describe('pickMimeType', () => {
  it('prefers webm/opus, falls back to mp4 (Safari), else null', () => {
    expect(pickMimeType(() => true)).toBe('audio/webm;codecs=opus');
    expect(pickMimeType((m) => m === 'audio/mp4')).toBe('audio/mp4');
    expect(pickMimeType(() => false)).toBeNull();
  });
});

describe('uploadFileName', () => {
  it('names the part by container, ignoring parameters', () => {
    expect(uploadFileName('audio/webm;codecs=opus')).toBe('take.webm');
    expect(uploadFileName('audio/mp4')).toBe('take.m4a');
    expect(uploadFileName('AUDIO/OGG; codecs=opus')).toBe('take.ogg');
    expect(uploadFileName('')).toBe('take.bin');
  });
});
