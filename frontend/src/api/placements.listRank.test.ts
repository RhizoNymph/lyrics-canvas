import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createPlacementApi } from './placements';
import { PlacementError, describePlacementError } from '@/lib/placements';

interface Seen {
  method: string | undefined;
  url: string | undefined;
  params: unknown;
  body: unknown;
}

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown }) {
  const seen: Seen[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({
      method: config.method,
      url: config.url,
      params: config.params,
      body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    });
    const { status, data } = respond(config);
    const response: AxiosResponse = { status, statusText: '', data, headers: {}, config };
    if (status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  const http = axios.create({ adapter, headers: { 'Content-Type': 'application/json' } });
  return { api: createPlacementApi(http), seen };
}

const invalidRank = () => ({
  status: 422,
  data: { detail: { error: 'invalid_list_rank', message: 'bad key' } },
});

describe('setListRank', () => {
  it('PUTs only list_rank, with canvas_id as a query parameter, and returns the snippet', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: { id: 's1', list_rank: 'a0V' } }));
    await expect(api.setListRank('s1', 'c1', 'a0V')).resolves.toEqual({ id: 's1', list_rank: 'a0V' });
    expect(seen).toEqual([
      { method: 'put', url: '/api/snippets/s1', params: { canvas_id: 'c1' }, body: { list_rank: 'a0V' } },
    ]);
  });

  it('maps invalid_list_rank to a typed error of the save_list_order operation', async () => {
    const { api } = harness(invalidRank);
    const err = await api.setListRank('s1', 'c1', 'a00').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(PlacementError);
    expect(err).toMatchObject({ kind: 'invalid_list_rank', operation: 'save_list_order', status: 422 });
    expect(describePlacementError(err as PlacementError)).toMatch(/order/i);
  });

  it('maps placement_not_found', async () => {
    const { api } = harness(() => ({ status: 404, data: { detail: { error: 'placement_not_found', message: 'm' } } }));
    await expect(api.setListRank('s1', 'c1', 'a0')).rejects.toMatchObject({ kind: 'placement_not_found' });
  });

  it('encodes the snippet id', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: {} }));
    await api.setListRank('s/1', 'c1', 'a0');
    expect(seen[0].url).toBe('/api/snippets/s%2F1');
  });
});

describe('setListRanks', () => {
  it('PATCHes one batch-position call whose items carry only list_rank', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: null }));
    await api.setListRanks('c1', [
      { id: 'a', list_rank: 'a0' },
      { id: 'b', list_rank: 'a1' },
    ]);
    expect(seen).toEqual([
      {
        method: 'patch',
        url: '/api/snippets/batch-position',
        params: undefined,
        body: {
          canvas_id: 'c1',
          updates: [
            { id: 'a', list_rank: 'a0' },
            { id: 'b', list_rank: 'a1' },
          ],
        },
      },
    ]);
  });

  it('maps invalid_list_rank and network failures', async () => {
    const { api } = harness(invalidRank);
    await expect(api.setListRanks('c1', [{ id: 'a', list_rank: 'x' }])).rejects.toMatchObject({
      kind: 'invalid_list_rank',
      operation: 'save_list_order',
    });
  });
});

describe('batchPosition', () => {
  it('sends items mixing positions and ranks as given', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: null }));
    await api.batchPosition('c1', [
      { id: 'a', position_x: 1, position_y: 2 },
      { id: 'b', position_x: 3, position_y: 4, list_rank: 'a0' },
      { id: 'c', list_rank: 'a1' },
    ]);
    expect(seen[0].body).toEqual({
      canvas_id: 'c1',
      updates: [
        { id: 'a', position_x: 1, position_y: 2 },
        { id: 'b', position_x: 3, position_y: 4, list_rank: 'a0' },
        { id: 'c', list_rank: 'a1' },
      ],
    });
  });
});
