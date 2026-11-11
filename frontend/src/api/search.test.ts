import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createSearchApi } from './search';
import { SearchError } from '@/lib/search';
import type { GlobalSearchResponse } from '@/types/search';

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown } | 'network') {
  const seen: { method?: string; url?: string; params: unknown }[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({ method: config.method, url: config.url, params: config.params });
    const r = respond(config);
    if (r === 'network') throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
    const response: AxiosResponse = { status: r.status, statusText: '', data: r.data, headers: {}, config };
    if (r.status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  return { api: createSearchApi(axios.create({ adapter })), seen };
}

const body: GlobalSearchResponse = {
  canvases: [{ id: 'c1', title: 'Night songs', updated_at: '2026-11-01T00:00:00Z' }],
  snippets: [
    {
      snippet_id: 's1',
      snippet_type: 'verse',
      content_updated_at: '2026-11-02T00:00:00Z',
      excerpt: 'black cat at night',
      canvases: [{ id: 'c1', title: 'Night songs' }],
    },
  ],
};

describe('searchApi.searchAll', () => {
  it('GETs /api/search with q (and limit when given)', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: body }));
    await expect(api.searchAll('night')).resolves.toEqual(body);
    await api.searchAll('night', { limit: 20 });
    expect(seen).toEqual([
      { method: 'get', url: '/api/search', params: { q: 'night' } },
      { method: 'get', url: '/api/search', params: { q: 'night', limit: 20 } },
    ]);
  });

  it('rejects with a typed invalid_search_query', async () => {
    const { api } = harness(() => ({ status: 422, data: { detail: { error: 'invalid_search_query', message: 'q must be 1 to 200 characters' } } }));
    const err = await api.searchAll('x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(SearchError);
    expect(err).toMatchObject({ kind: 'invalid_search_query', status: 422 });
  });

  it('rejects with network when there is no response', async () => {
    const { api } = harness(() => 'network');
    await expect(api.searchAll('x')).rejects.toMatchObject({ kind: 'network', status: null });
  });
});
