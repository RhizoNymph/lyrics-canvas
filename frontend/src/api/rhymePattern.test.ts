import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createRhymePatternApi } from './rhymePattern';
import { PatternError } from '@/lib/rhyme/pattern/errors';
import { buildDetectRequest, buildSuggestRequest } from '@/lib/rhyme/pattern/request';
import type { PatternDetectResponse, PatternSuggestResponse } from '@/types/rhymePattern';
import { pline, pslot } from '@/lib/rhyme/pattern/fixtures';

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown } | 'network') {
  const seen: { method?: string; url?: string; body: unknown }[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({ method: config.method, url: config.url, body: JSON.parse(String(config.data)) });
    const r = respond(config);
    if (r === 'network') throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
    const response: AxiosResponse = { status: r.status, statusText: '', data: r.data, headers: {}, config };
    if (r.status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  return { api: createRhymePatternApi(axios.create({ adapter })), seen };
}

const session = { canvasId: 'c1', snippetId: 'sn', lineStart: 0, lineEnd: 2 };
const detected: PatternDetectResponse = { lines: [pline(0, 'a b'), pline(1, 'c d')], slots: [pslot('s0', [[0, 1, 2], [1, 1, 2]])], elapsed_ms: 2 };
const edit = { lines: [{ line: 0, wordCount: 2 }, { line: 1, wordCount: 2 }], slots: [{ id: 's0', on: true, members: [{ line: 0, start: 1, end: 2 }] }], nextUserId: 0 };
const params = { minScore: 0.5, sources: ['dictionary' as const], includeRare: false, tagFilter: null, excludeIds: [] };

describe('rhymePatternApi', () => {
  it('POSTs detect and returns the body', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: detected }));
    const req = buildDetectRequest(session, 0.75);
    await expect(api.detect(req)).resolves.toEqual(detected);
    expect(seen).toEqual([{ method: 'post', url: '/api/nlp/rhyme-pattern/detect', body: req }]);
  });

  it('POSTs suggest', async () => {
    const body: PatternSuggestResponse = { slots: [], line_matches: [], line_match_total: 0, elapsed_ms: 1 };
    const { api, seen } = harness(() => ({ status: 200, data: body }));
    const req = buildSuggestRequest(session, edit, params, { kind: 'first' });
    if (req === null) throw new Error('expected a request');
    await expect(api.suggest(req)).resolves.toEqual(body);
    expect(seen[0]).toEqual({ method: 'post', url: '/api/nlp/rhyme-pattern/suggest', body: req });
  });

  it('rejects with typed errors labelled by operation', async () => {
    const { api } = harness(() => ({ status: 422, data: { detail: { error: 'pattern_slot_order', message: 'order' } } }));
    const req = buildSuggestRequest(session, edit, params, { kind: 'first' });
    if (req === null) throw new Error('expected a request');
    const e = await api.suggest(req, 'more').catch((x: unknown) => x);
    expect(e).toBeInstanceOf(PatternError);
    expect(e).toMatchObject({ kind: 'slot_order', operation: 'more', status: 422 });
    const net = harness(() => 'network');
    await expect(net.api.detect(buildDetectRequest(session, 0.75))).rejects.toMatchObject({ kind: 'network', operation: 'detect' });
  });
});
