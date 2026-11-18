import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createScoreApi } from './score';
import { exportFallbackFilename } from '@/lib/scoreTools/exportFile';
import { ScoreApiError } from '@/types/scoreTools';
import type { ScoreDraftResponse } from '@/types/scoreTools';
import type { Score } from '@/types/score';

interface Seen {
  method: string | undefined;
  url: string | undefined;
  data: unknown;
  params: unknown;
  responseType: string | undefined;
  signal: unknown;
}

interface Reply {
  status: number;
  data: unknown;
  headers?: Record<string, string>;
}

function harness(respond: (config: InternalAxiosRequestConfig) => Reply) {
  const seen: Seen[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({
      method: config.method,
      url: config.url,
      data: config.data,
      params: config.params,
      responseType: config.responseType,
      signal: config.signal,
    });
    const { status, data, headers = {} } = respond(config);
    const response: AxiosResponse = { status, statusText: '', data, headers, config };
    if (status >= 400) {
      throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    }
    return response;
  };
  const http = axios.create({ adapter, headers: { 'Content-Type': 'application/json' } });
  return { api: createScoreApi(http), seen };
}

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [{ notes: [{ s: 0, t: 0, d: 24, a: 1 }] }],
};

const draft: ScoreDraftResponse = { score, bpm_estimated: null, unplaced: 0, unmatched_words: 0 };

describe('scoreApi.draft', () => {
  it('posts a grid draft to the snippet route and returns the draft', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: draft }));
    const got = await api.draft('s/1', { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 });
    expect(got).toEqual(draft);
    expect(seen[0].method).toBe('post');
    expect(seen[0].url).toBe('/api/snippets/s%2F1/score/draft');
    expect(JSON.parse(String(seen[0].data))).toEqual({ source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 });
  });

  it('posts a recording draft with an explicit null bpm', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: { ...draft, bpm_estimated: 88 } }));
    const got = await api.draft('s1', { source: 'recording', recording_id: 'r1', meter: { num: 6, den: 8 }, bpm: null });
    expect(got.bpm_estimated).toBe(88);
    expect(JSON.parse(String(seen[0].data))).toEqual({
      source: 'recording',
      recording_id: 'r1',
      meter: { num: 6, den: 8 },
      bpm: null,
    });
  });

  it('passes the abort signal through', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: draft }));
    const abort = new AbortController();
    await api.draft('s1', { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 }, abort.signal);
    expect(seen[0].signal).toBe(abort.signal);
  });

  it.each([
    [404, 'snippet_not_found'],
    [404, 'recording_not_found'],
    [422, 'recording_has_no_timings'],
    [422, 'draft_empty'],
  ] as const)('rejects %i %s with a typed error', async (status, code) => {
    const { api } = harness(() => ({ status, data: { detail: { error: code, message: code } } }));
    const err = await api.draft('s1', { source: 'recording', recording_id: 'r1', meter: { num: 4, den: 4 }, bpm: null }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ScoreApiError);
    expect((err as ScoreApiError).kind).toBe(code);
    expect((err as ScoreApiError).operation).toBe('draft');
    expect((err as ScoreApiError).status).toBe(status);
  });

  it('rejects invalid_score with its reason', async () => {
    const { api } = harness(() => ({ status: 422, data: { detail: { error: 'invalid_score', reason: 'exceeds_max_ticks' } } }));
    const err = (await api
      .draft('s1', { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 })
      .catch((e: unknown) => e)) as ScoreApiError;
    expect(err.kind).toBe('invalid_score');
    expect(err.reason).toBe('exceeds_max_ticks');
  });

  it('rejects a response without a score as http (never resolves with a broken draft)', async () => {
    const { api } = harness(() => ({ status: 200, data: { bpm_estimated: null } }));
    const err = (await api
      .draft('s1', { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 })
      .catch((e: unknown) => e)) as ScoreApiError;
    expect(err).toBeInstanceOf(ScoreApiError);
    expect(err.kind).toBe('http');
  });
});

describe('scoreApi.exportScore', () => {
  it('downloads MIDI as a blob under the server file name', async () => {
    const blob = new Blob([new Uint8Array([0x4d, 0x54, 0x68, 0x64])], { type: 'audio/midi' });
    const { api, seen } = harness(() => ({
      status: 200,
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="Night drive.mid"' },
    }));
    const got = await api.exportScore('s1', 'midi');
    expect(got.blob).toBe(blob);
    expect(got.filename).toBe('Night drive.mid');
    expect(seen[0].method).toBe('get');
    expect(seen[0].url).toBe('/api/snippets/s1/score/export');
    expect(seen[0].params).toEqual({ format: 'midi' });
    expect(seen[0].responseType).toBe('blob');
  });

  it('decodes an RFC 5987 file name', async () => {
    const { api } = harness(() => ({
      status: 200,
      data: new Blob(['<score-partwise/>']),
      headers: { 'content-disposition': "attachment; filename*=UTF-8''Caf%C3%A9.musicxml" },
    }));
    const got = await api.exportScore('s1', 'musicxml');
    expect(got.filename).toBe('Café.musicxml');
  });

  it('falls back to a snippet file name when the header is missing', async () => {
    const { api } = harness(() => ({ status: 200, data: new Blob(['x']) }));
    const got = await api.exportScore('0123456789abcdef', 'musicxml');
    expect(got.filename).toBe(exportFallbackFilename('0123456789abcdef', 'musicxml'));
  });

  it('reads the typed code out of a blob error body', async () => {
    const body = new Blob([JSON.stringify({ detail: { error: 'score_not_found', message: 'no score' } })], { type: 'application/json' });
    const { api } = harness(() => ({ status: 404, data: body }));
    const err = (await api.exportScore('s1', 'midi').catch((e: unknown) => e)) as ScoreApiError;
    expect(err).toBeInstanceOf(ScoreApiError);
    expect(err.kind).toBe('score_not_found');
    expect(err.operation).toBe('export');
    expect(err.message).toBe('no score');
  });

  it('falls back on the status for a non-JSON blob error body', async () => {
    const { api } = harness(() => ({ status: 502, data: new Blob(['<html>bad gateway</html>']) }));
    const err = (await api.exportScore('s1', 'midi').catch((e: unknown) => e)) as ScoreApiError;
    expect(err.kind).toBe('server_unavailable');
  });
});
