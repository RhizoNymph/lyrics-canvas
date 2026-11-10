import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';
import { describeVoiceError, isRecordingGone, toVoiceError } from './errors';
import { VoiceError } from './types';

function axiosError(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, {}, { status, statusText: '', data, headers: {}, config });
}

describe('dataset error codes', () => {
  it.each(['recording_not_found', 'invalid_verification', 'export_too_large'] as const)('maps %s', (code) => {
    expect(toVoiceError(axiosError(400, { detail: { error: code, message: 'm' } })).kind).toBe(code);
  });

  it('lets a caller override the status fallback (an export 413 is export_too_large)', () => {
    expect(toVoiceError(axiosError(413, '<html>nginx</html>'), { 413: 'export_too_large' }).kind).toBe('export_too_large');
    // A body code still wins over the override.
    expect(toVoiceError(axiosError(413, { detail: { error: 'audio_too_large', message: 'm' } }), { 413: 'export_too_large' }).kind).toBe(
      'audio_too_large',
    );
    // Without an override the upload fallback stays.
    expect(toVoiceError(axiosError(413, null)).kind).toBe('audio_too_large');
  });

  it('has human messages for every new kind', () => {
    for (const kind of ['recording_not_found', 'invalid_verification', 'export_too_large'] as const) {
      expect(describeVoiceError(new VoiceError(kind, 'm')).title.length).toBeGreaterThan(5);
    }
  });

  it('treats both 404 shapes as a recording that is gone', () => {
    expect(isRecordingGone(new VoiceError('recording_not_found', 'm', 404))).toBe(true);
    expect(isRecordingGone(new VoiceError('not_found', 'm', 404))).toBe(true);
    expect(isRecordingGone(new VoiceError('http', 'm', 500))).toBe(false);
  });
});
