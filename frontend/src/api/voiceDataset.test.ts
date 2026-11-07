import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { buildTakeMeta, createVoiceApi } from './voice';
import { VoiceError } from '@/lib/voice/types';
import type { RecordingResponse, Take } from '@/lib/voice/types';

interface Seen {
  method: string | undefined;
  url: string | undefined;
  data: unknown;
  params: unknown;
  responseType: string | undefined;
}

type Reply = { status: number; data: unknown; headers?: Record<string, string> };

function harness(respond: (config: InternalAxiosRequestConfig) => Reply) {
  const seen: Seen[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({ method: config.method, url: config.url, data: config.data, params: config.params, responseType: config.responseType });
    const { status, data, headers } = respond(config);
    const response: AxiosResponse = { status, statusText: '', data, headers: headers ?? {}, config };
    if (status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  const http = axios.create({ adapter, headers: { 'Content-Type': 'application/json' } });
  return { api: createVoiceApi(http), seen };
}

const recording: RecordingResponse = {
  id: 'r1',
  snippet_id: 's1',
  mime_type: 'audio/webm',
  duration_ms: 1000,
  byte_size: 3,
  transcript: 'a\nb',
  split_mode: 'taps',
  created_at: '2026-11-01T00:00:00Z',
  lines: ['a', 'b'],
  has_timings: false,
  words: null,
  line_spans: null,
  language: 'en',
  initial_prompt: null,
  verified_text: 'a\nb',
  verified_at: '2026-11-02T00:00:00Z',
};

const take: Take = { blob: new Blob(['x'], { type: 'audio/webm' }), mimeType: 'audio/webm', durationMs: 1000, breaksMs: [] };

describe('vocabulary hints in the take meta', () => {
  it('sends the preference as meta.vocabulary_hints', async () => {
    expect(buildTakeMeta(take, { kind: 'append', snippet_id: 's' }, true).vocabulary_hints).toBe(true);
    expect(buildTakeMeta(take, { kind: 'append', snippet_id: 's' }, false).vocabulary_hints).toBe(false);
    const { api, seen } = harness(() => ({ status: 201, data: {} }));
    await api.createTake('c', take, { kind: 'append', snippet_id: 's' }, undefined, { vocabularyHints: false });
    const meta = JSON.parse(String((seen[0].data as FormData).get('meta')));
    expect(meta.vocabulary_hints).toBe(false);
  });
});

describe('verification endpoints', () => {
  it('PUTs the text and returns the recording', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: recording }));
    expect(await api.saveVerification('r 1', 'a\nb')).toEqual(recording);
    expect(seen[0]).toMatchObject({ method: 'put', url: '/api/recordings/r%201/verification' });
    expect(JSON.parse(String(seen[0].data))).toEqual({ text: 'a\nb' });
  });

  it('DELETEs the verification and returns the cleared recording', async () => {
    const cleared = { ...recording, verified_text: null, verified_at: null };
    const { api, seen } = harness(() => ({ status: 200, data: cleared }));
    expect(await api.clearVerification('r1')).toEqual(cleared);
    expect(seen[0]).toMatchObject({ method: 'delete', url: '/api/recordings/r1/verification' });
  });

  it.each([
    [422, 'invalid_verification'],
    [404, 'recording_not_found'],
  ] as const)('maps %i %s to a typed error', async (status, code) => {
    const { api } = harness(() => ({ status, data: { detail: { error: code, message: 'nope' } } }));
    const err = await api.saveVerification('r1', 'x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(VoiceError);
    expect((err as VoiceError).kind).toBe(code);
    const err2 = await api.clearVerification('r1').catch((e: unknown) => e);
    expect((err2 as VoiceError).kind).toBe(code);
  });
});

describe('stats', () => {
  it('GETs the stats', async () => {
    const stats = { total: 3, verified: 1, total_duration_ms: 9000, verified_duration_ms: 3000, with_timings: 2 };
    const { api, seen } = harness(() => ({ status: 200, data: stats }));
    expect(await api.datasetStats()).toEqual(stats);
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/recordings/stats' });
  });
});

describe('dataset export', () => {
  it('downloads the zip as a blob with the server filename and the include flag', async () => {
    const zip = new Blob(['PK'], { type: 'application/zip' });
    const { api, seen } = harness(() => ({
      status: 200,
      data: zip,
      headers: { 'content-disposition': 'attachment; filename="lyrics-canvas-voice-dataset-20261106T101500Z.zip"' },
    }));
    const out = await api.exportDataset({ includeUnverified: true });
    expect(out.blob).toBe(zip);
    expect(out.filename).toBe('lyrics-canvas-voice-dataset-20261106T101500Z.zip');
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/recordings/export', responseType: 'blob' });
    expect(seen[0].params).toEqual({ include_unverified: true });
  });

  it('sends include_unverified=false by default and falls back on a client-stamped name', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: new Blob(['PK']) }));
    const out = await api.exportDataset({ includeUnverified: false }, undefined, () => new Date(Date.UTC(2026, 0, 2, 3, 4, 5)));
    expect(out.filename).toBe('lyrics-canvas-voice-dataset-20260102T030405Z.zip');
    expect(seen[0].params).toEqual({ include_unverified: false });
  });

  it('reads a JSON error body that arrives as a Blob (responseType blob)', async () => {
    const body = new Blob([JSON.stringify({ detail: { error: 'export_too_large', message: 'too much audio' } })], { type: 'application/json' });
    const { api } = harness(() => ({ status: 413, data: body }));
    const err = await api.exportDataset({ includeUnverified: false }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(VoiceError);
    expect((err as VoiceError).kind).toBe('export_too_large');
    expect((err as VoiceError).message).toBe('too much audio');
    expect((err as VoiceError).status).toBe(413);
  });

  it('maps a bare 413 (nginx) on export to export_too_large, not audio_too_large', async () => {
    const { api } = harness(() => ({ status: 413, data: new Blob(['<html>413</html>'], { type: 'text/html' }) }));
    const err = await api.exportDataset({ includeUnverified: false }).catch((e: unknown) => e);
    expect((err as VoiceError).kind).toBe('export_too_large');
  });
});

describe('listRecordings with the extended shape', () => {
  it('passes the new fields through untouched', async () => {
    const { api } = harness(() => ({ status: 200, data: [recording] }));
    expect(await api.listRecordings('s1')).toEqual([recording]);
  });
});
