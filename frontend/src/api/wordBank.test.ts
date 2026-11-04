import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createWordBankApi } from './wordBank';
import { WordBankApiError } from '@/lib/wordBank/errors';

interface Seen {
  method: string | undefined;
  url: string | undefined;
  data: unknown;
  params: unknown;
}

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown; headers?: Record<string, string> }) {
  const seen: Seen[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({
      method: config.method,
      url: config.url,
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params,
    });
    const { status, data, headers = {} } = respond(config);
    const response: AxiosResponse = { status, statusText: '', data, headers, config };
    if (status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  const http = axios.create({ adapter, headers: { 'Content-Type': 'application/json' } });
  return { api: createWordBankApi(http), seen };
}

const word = { id: 'w1', text: 'night', tags: [], created_at: '2026-11-04T00:00:00Z' };

describe('createWordBankApi', () => {
  it('lists the bank', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: [word] }));
    expect(await api.list()).toEqual([word]);
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/word-bank' });
  });

  it('adds a batch with tag ids', async () => {
    const { api, seen } = harness(() => ({ status: 201, data: { created: [word], existing: [] } }));
    expect(await api.add({ words: ['night'], tag_ids: ['t1'] })).toEqual({ created: [word], existing: [] });
    expect(seen[0]).toMatchObject({ method: 'post', url: '/api/word-bank', data: { words: ['night'], tag_ids: ['t1'] } });
  });

  it('renames with PUT and an encoded id', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: { ...word, text: 'Night' } }));
    await api.rename('a/b', 'Night');
    expect(seen[0]).toMatchObject({ method: 'put', url: '/api/word-bank/a%2Fb', data: { text: 'Night' } });
  });

  it('deletes with POST /delete', async () => {
    const { api, seen } = harness(() => ({ status: 204, data: '' }));
    await expect(api.delete(['w1', 'w2'])).resolves.toBeUndefined();
    expect(seen[0]).toMatchObject({ method: 'post', url: '/api/word-bank/delete', data: { ids: ['w1', 'w2'] } });
  });

  it('links and unlinks with POST /tags', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: { words: [word] } }));
    await api.setTag(['w1'], 't1', 'link');
    await api.setTag(['w1'], 't1', 'unlink');
    expect(seen.map((s) => s.data)).toEqual([
      { word_ids: ['w1'], tag_id: 't1', op: 'link' },
      { word_ids: ['w1'], tag_id: 't1', op: 'unlink' },
    ]);
    expect(seen[0].url).toBe('/api/word-bank/tags');
  });

  it('posts rhymes and families bodies as given', async () => {
    const { api, seen } = harness((c) =>
      c.url?.endsWith('rhymes')
        ? { status: 200, data: { anchor: { text: 'x', rhyme_key: [], syllable_count: 1 }, matches: [] } }
        : { status: 200, data: { families: [], unmatched: [] } },
    );
    await api.rhymes({ word_id: 'w1', min_score: 0.5, tag_filter: 'love' });
    await api.families({ min_score: 0.8 });
    expect(seen[0]).toMatchObject({ url: '/api/word-bank/rhymes', data: { word_id: 'w1', min_score: 0.5, tag_filter: 'love' } });
    expect(seen[1]).toMatchObject({ url: '/api/word-bank/families', data: { min_score: 0.8 } });
  });

  it('searches related words with q and limit params', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: { query: 'sea', results: [] } }));
    await api.related('sea');
    expect(seen[0]).toMatchObject({ method: 'get', url: '/api/word-bank/related', params: { q: 'sea', limit: 30 } });
  });

  it('rejects with typed errors, operation per call', async () => {
    const { api } = harness((c) =>
      c.url === '/api/word-bank/related'
        ? { status: 503, data: { detail: { error: 'related_words_warming', message: 'w' } }, headers: { 'retry-after': '10' } }
        : { status: 422, data: { detail: { error: 'invalid_word', message: 'bad', index: 2 } } },
    );
    const add = await api.add({ words: ['a', 'b', 'c3'], tag_ids: [] }).catch((e: unknown) => e);
    expect(add).toBeInstanceOf(WordBankApiError);
    expect((add as WordBankApiError).detail).toEqual({ kind: 'invalid_word', index: 2 });
    expect((add as WordBankApiError).operation).toBe('add_words');
    const rel = await api.related('x').catch((e: unknown) => e);
    expect((rel as WordBankApiError).detail).toEqual({ kind: 'related_words_warming', retryAfterSeconds: 10 });
    const unlink = await api.setTag(['w'], 't', 'unlink').catch((e: unknown) => e);
    expect((unlink as WordBankApiError).operation).toBe('unlink_words');
  });
});
