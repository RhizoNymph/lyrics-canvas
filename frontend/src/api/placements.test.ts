import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createPlacementApi } from './placements';
import { PlacementError } from '@/lib/placements';

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

const ok = (data: unknown, status = 200) => () => ({ status, data });
const fail = (status: number, error: string) => () => ({ status, data: { detail: { error, message: 'm' } } });

describe('renameCanvas', () => {
  it('PUTs the title to the canvas', async () => {
    const { api, seen } = harness(ok({ id: 'c1', title: 'New' }));
    await expect(api.renameCanvas('c1', 'New')).resolves.toEqual({ id: 'c1', title: 'New' });
    expect(seen).toEqual([{ method: 'put', url: '/api/canvases/c1', params: undefined, body: { title: 'New' } }]);
  });

  it('rejects with a typed invalid_canvas_title', async () => {
    const { api } = harness(fail(422, 'invalid_canvas_title'));
    const err = await api.renameCanvas('c1', 'x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(PlacementError);
    expect(err).toMatchObject({ kind: 'invalid_canvas_title', operation: 'rename_canvas', status: 422 });
  });
});

describe('createCanvas / listCanvases', () => {
  it('POSTs the title and GETs the list', async () => {
    const { api, seen } = harness((config) => ({ status: config.method === 'post' ? 201 : 200, data: config.method === 'post' ? { id: 'n' } : [] }));
    await expect(api.createCanvas('Hooks')).resolves.toEqual({ id: 'n' });
    await expect(api.listCanvases()).resolves.toEqual([]);
    expect(seen.map((s) => [s.method, s.url, s.body])).toEqual([
      ['post', '/api/canvases', { title: 'Hooks' }],
      ['get', '/api/canvases', undefined],
    ]);
  });
});

describe('updateSnippet', () => {
  it('PUTs with canvas_id as a query parameter', async () => {
    const { api, seen } = harness(ok({ id: 's1', canvas_id: 'c1' }));
    await api.updateSnippet('s1', 'c1', { raw_text: 'hi', position_x: 3 });
    expect(seen).toEqual([
      { method: 'put', url: '/api/snippets/s1', params: { canvas_id: 'c1' }, body: { raw_text: 'hi', position_x: 3 } },
    ]);
  });

  it('maps placement_not_found', async () => {
    const { api } = harness(fail(404, 'placement_not_found'));
    await expect(api.updateSnippet('s1', 'c1', {})).rejects.toMatchObject({ kind: 'placement_not_found', operation: 'update_snippet' });
  });
});

describe('batchPosition', () => {
  it('PATCHes canvas_id with the updates', async () => {
    const { api, seen } = harness(ok(null));
    await api.batchPosition('c1', [{ id: 's1', position_x: 1, position_y: 2 }]);
    expect(seen).toEqual([
      {
        method: 'patch',
        url: '/api/snippets/batch-position',
        params: undefined,
        body: { canvas_id: 'c1', updates: [{ id: 's1', position_x: 1, position_y: 2 }] },
      },
    ]);
  });
});

describe('removeFromCanvas', () => {
  it('DELETEs the placement and returns snippet_deleted', async () => {
    const { api, seen } = harness(ok({ snippet_deleted: true }));
    await expect(api.removeFromCanvas('c1', 's1')).resolves.toEqual({ snippet_deleted: true });
    expect(seen[0]).toMatchObject({ method: 'delete', url: '/api/canvases/c1/snippets/s1' });
  });

  it('maps placement_not_found', async () => {
    const { api } = harness(fail(404, 'placement_not_found'));
    await expect(api.removeFromCanvas('c1', 's1')).rejects.toMatchObject({ kind: 'placement_not_found' });
  });
});

describe('deleteEverywhere', () => {
  it('DELETEs the snippet', async () => {
    const { api, seen } = harness(ok(undefined, 204));
    await expect(api.deleteEverywhere('s1')).resolves.toBeUndefined();
    expect(seen[0]).toMatchObject({ method: 'delete', url: '/api/snippets/s1' });
  });
});

describe('transfer', () => {
  const body = { target_canvas_id: 't', placed: ['a'], already_on_target: ['b'], removed_from_source: false };

  it('POSTs the request to the source canvas transfer route', async () => {
    const { api, seen } = harness(ok(body));
    await expect(api.transfer('c1', { snippet_ids: ['a', 'b'], target_canvas_id: 't', mode: 'show' })).resolves.toEqual(body);
    expect(seen).toEqual([
      {
        method: 'post',
        url: '/api/canvases/c1/snippets/transfer',
        params: undefined,
        body: { snippet_ids: ['a', 'b'], target_canvas_id: 't', mode: 'show' },
      },
    ]);
  });

  it.each([
    [422, 'same_canvas'],
    [404, 'canvas_not_found'],
    [404, 'placement_not_found'],
  ])('maps %i %s', async (status, code) => {
    const { api } = harness(fail(status, code));
    await expect(api.transfer('c1', { snippet_ids: ['a'], target_canvas_id: 't', mode: 'move' })).rejects.toMatchObject({
      kind: code,
      operation: 'transfer',
    });
  });

  it('encodes ids in the path', async () => {
    const { api, seen } = harness(ok({ snippet_deleted: false }));
    await api.removeFromCanvas('c/1', 's 1');
    expect(seen[0].url).toBe('/api/canvases/c%2F1/snippets/s%201');
  });
});
