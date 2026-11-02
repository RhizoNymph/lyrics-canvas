import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';
import { captureErrorFromException, describeCaptureError, describeVoiceError, toVoiceError } from './errors';
import { CaptureError, VOICE_SERVER_ERROR_CODES, VoiceError } from './types';

function axiosError(status: number | null, data: unknown, code?: string): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response =
    status === null
      ? undefined
      : { status, statusText: '', data, headers: {}, config };
  return new AxiosError('Request failed', code ?? 'ERR_BAD_RESPONSE', config, {}, response);
}

describe('toVoiceError', () => {
  it.each(VOICE_SERVER_ERROR_CODES)('maps detail.error %s to its kind with the server message', (code) => {
    const err = toVoiceError(axiosError(422, { detail: { error: code, message: `msg ${code}` } }));
    expect(err).toBeInstanceOf(VoiceError);
    expect(err.kind).toBe(code);
    expect(err.message).toBe(`msg ${code}`);
    expect(err.status).toBe(422);
  });

  it('falls back on the status when the body carries no contract code', () => {
    expect(toVoiceError(axiosError(413, '<html>Request Entity Too Large</html>')).kind).toBe('audio_too_large');
    expect(toVoiceError(axiosError(415, null)).kind).toBe('unsupported_audio_type');
    expect(toVoiceError(axiosError(502, '<html>Bad Gateway</html>')).kind).toBe('server_unavailable');
    expect(toVoiceError(axiosError(503, null)).kind).toBe('server_unavailable');
    expect(toVoiceError(axiosError(504, null)).kind).toBe('server_unavailable');
    expect(toVoiceError(axiosError(404, { detail: 'Not Found' })).kind).toBe('not_found');
    const other = toVoiceError(axiosError(500, { detail: 'boom' }));
    expect(other.kind).toBe('http');
    expect(other.status).toBe(500);
    expect(other.message).toBe('boom');
  });

  it('ignores unknown codes in the detail object', () => {
    expect(toVoiceError(axiosError(400, { detail: { error: 'something_new', message: 'x' } })).kind).toBe('http');
  });

  it('treats FastAPI validation lists as plain http errors', () => {
    const err = toVoiceError(axiosError(422, { detail: [{ loc: ['body', 'audio'], msg: 'field required' }] }));
    expect(err.kind).toBe('http');
    expect(err.status).toBe(422);
  });

  it('maps a missing response to network and a cancellation to aborted', () => {
    expect(toVoiceError(axiosError(null, undefined, 'ERR_NETWORK')).kind).toBe('network');
    expect(toVoiceError(axiosError(null, undefined, AxiosError.ERR_CANCELED)).kind).toBe('aborted');
  });

  it('passes VoiceErrors through and wraps other throwables', () => {
    const original = new VoiceError('stt_failed', 'x', 502);
    expect(toVoiceError(original)).toBe(original);
    expect(toVoiceError(new Error('weird')).kind).toBe('http');
    expect(toVoiceError('weird').message).toBe('weird');
  });
});

describe('describeVoiceError', () => {
  it('gives every kind a title and decides retry', () => {
    const kinds = [
      ...VOICE_SERVER_ERROR_CODES,
      'server_unavailable',
      'network',
      'aborted',
      'editor_save_failed',
      'not_found',
      'http',
    ] as const;
    for (const kind of kinds) {
      const d = describeVoiceError(new VoiceError(kind, 'm'));
      expect(d.title.length).toBeGreaterThan(0);
      expect(typeof d.retryable).toBe('boolean');
    }
  });

  it('offers retry for transient failures only', () => {
    const retry = (kind: VoiceError['kind']) => describeVoiceError(new VoiceError(kind, 'm')).retryable;
    expect(retry('network')).toBe(true);
    expect(retry('stt_unavailable')).toBe(true);
    expect(retry('stt_failed')).toBe(true);
    expect(retry('server_unavailable')).toBe(true);
    expect(retry('editor_save_failed')).toBe(true);
    expect(retry('http')).toBe(true);
    expect(retry('stt_disabled')).toBe(false);
    expect(retry('empty_transcript')).toBe(false);
    expect(retry('audio_too_large')).toBe(false);
    expect(retry('unsupported_audio_type')).toBe(false);
    expect(retry('invalid_meta')).toBe(false);
    expect(retry('snippet_not_found')).toBe(false);
    expect(retry('canvas_not_found')).toBe(false);
  });
});

describe('captureErrorFromException', () => {
  const dom = (name: string) => Object.assign(new Error(name), { name });

  it('maps getUserMedia DOMException names', () => {
    expect(captureErrorFromException(dom('NotAllowedError')).kind).toBe('permission_denied');
    expect(captureErrorFromException(dom('SecurityError')).kind).toBe('permission_denied');
    expect(captureErrorFromException(dom('NotFoundError')).kind).toBe('no_microphone');
    expect(captureErrorFromException(dom('OverconstrainedError')).kind).toBe('no_microphone');
    expect(captureErrorFromException(dom('NotReadableError')).kind).toBe('microphone_busy');
    expect(captureErrorFromException(dom('AbortError')).kind).toBe('microphone_busy');
    expect(captureErrorFromException(dom('Whatever')).kind).toBe('recorder_failed');
    expect(captureErrorFromException('x').kind).toBe('recorder_failed');
  });

  it('passes CaptureErrors through', () => {
    const e = new CaptureError('unsupported', 'x');
    expect(captureErrorFromException(e)).toBe(e);
  });

  it('describes every capture kind', () => {
    for (const kind of ['insecure_context', 'unsupported', 'permission_denied', 'no_microphone', 'microphone_busy', 'recorder_failed'] as const) {
      expect(describeCaptureError(new CaptureError(kind, 'm')).title.length).toBeGreaterThan(0);
    }
    expect(describeCaptureError(new CaptureError('insecure_context', 'm')).retryable).toBe(false);
    expect(describeCaptureError(new CaptureError('permission_denied', 'm')).retryable).toBe(true);
  });
});
