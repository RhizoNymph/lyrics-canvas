import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { buildTakeMeta, createVoiceApi } from './voice';
import { VoiceError } from '@/lib/voice/types';
import type { Take, VoiceTakeResponse } from '@/lib/voice/types';

interface Seen {
  method: string | undefined;
  url: string | undefined;
  data: unknown;
  contentType: unknown;
  responseType: string | undefined;
}

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown }) {
  const seen: Seen[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({
      method: config.method,
      url: config.url,
      data: config.data,
      contentType: config.headers.get('Content-Type'),
      responseType: config.responseType,
    });
    const { status, data } = respond(config);
    const response: AxiosResponse = { status, statusText: '', data, headers: {}, config };
    if (status >= 400) {
      throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    }
    return response;
  };
  // Same JSON default as the app's instance, to prove multipart is not JSON-encoded.
  const http = axios.create({ adapter, headers: { 'Content-Type': 'application/json' } });
  return { api: createVoiceApi(http), seen };
}

const take: Take = {
  blob: new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm;codecs=opus' }),
  mimeType: 'audio/webm;codecs=opus',
  durationMs: 4200,
  breaksMs: [900, 2100],
};

const response = { snippet: { id: 's1' }, recording: { id: 'r1' }, lines: ['a', 'b'], split_mode: 'taps', created: true };

describe('buildTakeMeta', () => {
  it('builds the contract meta for both targets', () => {
    expect(buildTakeMeta(take, { kind: 'new', position_x: 10, position_y: 20 })).toEqual({
      breaks_ms: [900, 2100],
      duration_ms: 4200,
      target: { kind: 'new', position_x: 10, position_y: 20 },
    });
    expect(buildTakeMeta(take, { kind: 'append', snippet_id: 'x' }).target).toEqual({ kind: 'append', snippet_id: 'x' });
  });
});

describe('voiceApi.createTake', () => {
  it('posts multipart with the audio part and JSON meta to the canvas voice-takes route', async () => {
    const { api, seen } = harness(() => ({ status: 201, data: response }));
    const out: VoiceTakeResponse = await api.createTake('c 1', take, { kind: 'append', snippet_id: 's1' });
    expect(out).toEqual(response);
    expect(seen).toHaveLength(1);
    expect(seen[0].method).toBe('post');
    expect(seen[0].url).toBe('/api/canvases/c%201/voice-takes');
    expect(seen[0].data).toBeInstanceOf(FormData);
    expect(String(seen[0].contentType)).toMatch(/^multipart\/form-data/);
    const form = seen[0].data as FormData;
    const audio = form.get('audio');
    expect(audio).toBeInstanceOf(Blob);
    expect((audio as File).name).toBe('take.webm');
    expect((audio as Blob).type).toBe('audio/webm;codecs=opus');
    expect((audio as Blob).size).toBe(3);
    expect(JSON.parse(String(form.get('meta')))).toEqual({
      breaks_ms: [900, 2100],
      duration_ms: 4200,
      target: { kind: 'append', snippet_id: 's1' },
    });
  });

  it.each([
    [422, 'empty_transcript'],
    [503, 'stt_disabled'],
    [503, 'stt_unavailable'],
    [502, 'stt_failed'],
    [415, 'unsupported_audio_type'],
    [413, 'audio_too_large'],
    [404, 'snippet_not_found'],
    [404, 'canvas_not_found'],
    [422, 'invalid_meta'],
  ] as const)('rejects with a typed VoiceError for %i %s', async (status, code) => {
    const { api } = harness(() => ({ status, data: { detail: { error: code, message: 'nope' } } }));
    const err = await api.createTake('c', take, { kind: 'new', position_x: 0, position_y: 0 }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(VoiceError);
    expect((err as VoiceError).kind).toBe(code);
    expect((err as VoiceError).status).toBe(status);
  });

  it('maps an abort to aborted', async () => {
    const http = axios.create({
      adapter: async (config) => {
        throw new AxiosError('canceled', AxiosError.ERR_CANCELED, config);
      },
    });
    const err = await createVoiceApi(http)
      .createTake('c', take, { kind: 'new', position_x: 0, position_y: 0 })
      .catch((e: unknown) => e);
    expect((err as VoiceError).kind).toBe('aborted');
  });
});

describe('recordings endpoints', () => {
  it('lists recordings of a snippet', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: [{ id: 'r1' }] }));
    expect(await api.listRecordings('s1')).toEqual([{ id: 'r1' }]);
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/snippets/s1/recordings' });
  });

  it('fetches audio as a blob through the authenticated instance', async () => {
    const blob = new Blob(['x'], { type: 'audio/webm' });
    const { api, seen } = harness(() => ({ status: 200, data: blob }));
    expect(await api.fetchAudio('r1')).toBe(blob);
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/recordings/r1/audio', responseType: 'blob' });
  });

  it('deletes a recording and maps failures', async () => {
    const { api, seen } = harness((config) => (config.url?.endsWith('gone') ? { status: 404, data: { detail: 'Not Found' } } : { status: 204, data: '' }));
    await expect(api.deleteRecording('r1')).resolves.toBeUndefined();
    expect(seen[0]).toMatchObject({ method: 'delete', url: '/api/recordings/r1' });
    const err = await api.deleteRecording('gone').catch((e: unknown) => e);
    expect((err as VoiceError).kind).toBe('not_found');
  });
});
