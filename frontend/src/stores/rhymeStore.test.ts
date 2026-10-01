import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { nlpApi, snippetApi } from '@/api/client';
import { useRhymeStore } from './rhymeStore';
import { useCanvasStore } from './canvasStore';
import { RhymeError, RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate, RhymeUnit, RhymeSuggestResponse, RhymeSuggestRequest } from '@/types/rhyme';
import type { Snippet } from '@/types';
import { MATERIALIZE_GAP_X, MATERIALIZE_STEP_Y } from '@/lib/rhyme/materializePosition';

vi.mock('@/api/client', () => ({
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const rhymeSuggest = vi.mocked(nlpApi.rhymeSuggest);
const createSnippet = vi.mocked(snippetApi.create);

function snippet(id: string, raw_text: string, stress: string, x = 0, y = 0): Snippet {
  return {
    id,
    canvas_id: 'c1',
    snippet_type: 'verse',
    grid_mode: 'auto',
    beats_per_line: null,
    grid_data: {
      lines: [{ cells: Array.from(stress, (s) => ({ syllable: 'x', stress: Number(s) })), syllable_count: stress.length, raw_text }],
    },
    raw_text,
    position_x: x,
    position_y: y,
    width: 200,
    height: 100,
    z_index: 0,
    group_id: null,
    tags: [],
    created_at: '',
    updated_at: '',
  };
}

function unit(id: string, text: string, key: string[]): RhymeUnit {
  return {
    id,
    text,
    provenance: id.startsWith('dict:') ? 'dictionary' : 'note',
    ephemeral: true,
    syllable_count: 1,
    stress: '1',
    rhyme_key: key,
    syllables: [{ onset: [], nucleus: key[0] ?? 'AH', coda: key.slice(1), stress: 1 }],
    grid_line: { cells: [{ syllable: text, stress: 1 }], syllable_count: 1, raw_text: text },
    tags: [],
  };
}

function cand(id: string, text: string, score: number, key: string[] = ['IH', 'N']): RhymeCandidate {
  return {
    ...unit(id, text, key),
    score,
    match_depth: 1,
    best_anchor_id: 'note:a:0:0',
    fits_slot: true,
    alignment: [{ cand_index: 0, anchor_index: 0, nucleus: 1, coda: 1, onset: 0.5, total: score }],
    source: id.startsWith('dict:') ? 'dictionary' : 'notes',
  };
}

function response(candidates: RhymeCandidate[], total = candidates.length): { data: RhymeSuggestResponse } {
  return { data: { anchors: [unit('note:a:0:0', 'hinge', ['IH', 'N', 'JH'])], candidates, total, elapsed_ms: 1 } };
}

function axiosErr(status: number | null): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'fail',
    'E',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data: { detail: 'nope' } }
  );
}

function lastRequest(): RhymeSuggestRequest {
  const call = rhymeSuggest.mock.calls[rhymeSuggest.mock.calls.length - 1];
  return call[0];
}

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([
      ['a', snippet('a', 'orange door hinge', '1010', 100, 100)],
      ['b', snippet('b', 'four inch', '11', 300, 300)],
    ]),
    selectedSnippetIds: new Set(['a']),
    viewport: { x: 0, y: 0, zoom: 1 },
    past: [],
    future: [],
  });
  useRhymeStore.getState().exit();
  useRhymeStore.setState({ k: 2, minScore: 0.5, sources: { notes: true, dictionary: true }, slotMode: 'from-selection', slot: null, includeRare: false });
});

/** Activate without the immediate query counting against the test's own expectations. */
function enterQuietly() {
  useRhymeStore.setState({ active: true, error: null });
}

describe('enter / exit', () => {
  it('enter activates and queries immediately when there is a selection', async () => {
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    useRhymeStore.getState().enter();
    expect(useRhymeStore.getState().active).toBe(true);
    expect(rhymeSuggest).toHaveBeenCalledTimes(1);
    expect(lastRequest().anchors).toEqual([{ kind: 'snippet', snippet_id: 'a', line_index: null }]);
    await vi.waitFor(() => expect(useRhymeStore.getState().candidates.length).toBe(1));
  });

  it('enter with nothing selected and no typed anchor sends no request', () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set() });
    useRhymeStore.getState().enter();
    expect(rhymeSuggest).not.toHaveBeenCalled();
    expect(useRhymeStore.getState().candidates).toEqual([]);
  });

  it('exit clears session state and deactivates but keeps params', async () => {
    rhymeSuggest.mockResolvedValue(response([cand('dict:tin', 'tin', 0.9)]) as never);
    useRhymeStore.getState().enter();
    useRhymeStore.getState().setTypedAnchor('door');
    useRhymeStore.getState().setK(3);
    useRhymeStore.getState().setIncludeRare(true);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().dismiss('dict:tin');
    useRhymeStore.getState().excludeAnchor('a');
    useRhymeStore.setState({ materializeCounts: new Map([['a', 2]]) });
    expect(useRhymeStore.getState().dismissed.size).toBe(1);

    useRhymeStore.getState().exit();
    const after = useRhymeStore.getState();
    expect(after.active).toBe(false);
    expect(after.candidates).toEqual([]);
    expect(after.anchors).toEqual([]);
    expect(after.dismissed.size).toBe(0);
    expect(after.excludedAnchorIds.size).toBe(0);
    expect(after.materializeCounts.size).toBe(0);
    expect(after.typedAnchor).toBe('');
    expect(after.total).toBe(0);
    expect(after.offset).toBe(0);
    expect(after.error).toBeNull();
    expect(after.loading).toBe(false);
    expect(after.k).toBe(3);
    expect(after.includeRare).toBe(true);
  });

  it('exit leaves canvas snippets untouched', () => {
    const before = useCanvasStore.getState().snippets;
    useRhymeStore.getState().enter();
    useRhymeStore.getState().exit();
    expect(useCanvasStore.getState().snippets).toBe(before);
  });
});

describe('runQuery', () => {
  it('does nothing while inactive', async () => {
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest).not.toHaveBeenCalled();
  });

  it('builds the request from selection, typed text, params, and dismissed ids', async () => {
    enterQuietly();
    useRhymeStore.setState({ dismissed: new Set(['dict:orange']) });
    useRhymeStore.getState().setTypedAnchor(' door hinge ');
    useRhymeStore.getState().setMinScore(0.6);
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9), cand('note:b:0:1', 'inch', 0.7)], 77) as never);

    await useRhymeStore.getState().runQuery();

    expect(rhymeSuggest).toHaveBeenCalledTimes(1);
    expect(lastRequest()).toEqual({
      canvas_id: 'c1',
      anchors: [
        { kind: 'snippet', snippet_id: 'a', line_index: null },
        { kind: 'text', text: 'door hinge' },
      ],
      context: [],
      slot: { syllable_count: 4, stress: '1010' },
      k: 2,
      min_score: 0.6,
      sources: ['notes', 'dictionary'],
      include_rare: false,
      exclude_ids: ['dict:orange'],
      limit: RHYME_PAGE_SIZE,
      offset: 0,
      weights: null,
      dialect: null,
    });

    const s = useRhymeStore.getState();
    expect(s.candidates.map((c) => c.id)).toEqual(['dict:tin', 'note:b:0:1']);
    expect(s.total).toBe(77);
    expect(s.offset).toBe(0);
    expect(s.anchors[0].id).toBe('note:a:0:0');
    expect(s.slot).toEqual({ syllable_count: 4, stress: '1010' });
    expect(s.loading).toBe(false);
    expect(s.error).toBeNull();
  });

  it('uses the manual slot when slotMode is manual, and null when unset', async () => {
    enterQuietly();
    useRhymeStore.getState().setSlotMode('manual');
    useRhymeStore.getState().setSlot({ syllable_count: 2, stress: '10' });
    rhymeSuggest.mockResolvedValue(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().slot).toEqual({ syllable_count: 2, stress: '10' });

    useRhymeStore.getState().setSlot(null);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().slot).toBeNull();
  });

  it('sends only enabled sources and skips the call when none are enabled', async () => {
    enterQuietly();
    useRhymeStore.getState().toggleSource('notes');
    rhymeSuggest.mockResolvedValue(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().sources).toEqual(['dictionary']);

    useRhymeStore.getState().toggleSource('dictionary');
    rhymeSuggest.mockClear();
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest).not.toHaveBeenCalled();
    expect(useRhymeStore.getState().candidates).toEqual([]);
  });

  it('clears candidates without calling the API when there are no anchors', async () => {
    enterQuietly();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().candidates.length).toBe(1);

    useCanvasStore.setState({ selectedSnippetIds: new Set() });
    rhymeSuggest.mockClear();
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest).not.toHaveBeenCalled();
    expect(useRhymeStore.getState().candidates).toEqual([]);
    expect(useRhymeStore.getState().total).toBe(0);
  });

  it('sends include_rare when enabled', async () => {
    enterQuietly();
    useRhymeStore.getState().setIncludeRare(true);
    rhymeSuggest.mockResolvedValueOnce(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().include_rare).toBe(true);
  });

  it('stores a typed error and does not throw', async () => {
    enterQuietly();
    rhymeSuggest.mockRejectedValueOnce(axiosErr(422));
    await expect(useRhymeStore.getState().runQuery()).resolves.toBeUndefined();
    const s = useRhymeStore.getState();
    expect(s.error).toBeInstanceOf(RhymeError);
    expect(s.error?.kind).toBe('invalid_request');
    expect(s.error?.operation).toBe('query');
    expect(s.loading).toBe(false);
  });

  it('ignores a stale response that resolves after a newer query', async () => {
    enterQuietly();
    let resolveFirst: (v: unknown) => void = () => {};
    rhymeSuggest.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }) as never);
    const first = useRhymeStore.getState().runQuery();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:new', 'new', 0.9)]) as never);
    await useRhymeStore.getState().runQuery();
    resolveFirst(response([cand('dict:old', 'old', 0.9)]));
    await first;
    expect(useRhymeStore.getState().candidates.map((c) => c.id)).toEqual(['dict:new']);
    expect(useRhymeStore.getState().loading).toBe(false);
  });
});

describe('loadMore', () => {
  it('requests the next offset and appends without duplicates', async () => {
    enterQuietly();
    const firstPage = Array.from({ length: RHYME_PAGE_SIZE }, (_, i) => cand(`dict:w${i}`, `w${i}`, 0.9));
    rhymeSuggest.mockResolvedValueOnce(response(firstPage, 90) as never);
    await useRhymeStore.getState().runQuery();

    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:w0', 'w0', 0.9), cand('dict:extra', 'extra', 0.6)], 90) as never);
    await useRhymeStore.getState().loadMore();

    expect(lastRequest().offset).toBe(RHYME_PAGE_SIZE);
    expect(lastRequest().limit).toBe(RHYME_PAGE_SIZE);
    const s = useRhymeStore.getState();
    expect(s.offset).toBe(RHYME_PAGE_SIZE);
    expect(s.candidates.length).toBe(RHYME_PAGE_SIZE + 1);
    expect(s.candidates[s.candidates.length - 1].id).toBe('dict:extra');
  });

  it('does nothing when everything is already loaded', async () => {
    enterQuietly();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)], 1) as never);
    await useRhymeStore.getState().runQuery();
    rhymeSuggest.mockClear();
    await useRhymeStore.getState().loadMore();
    expect(rhymeSuggest).not.toHaveBeenCalled();
  });
});

describe('dismiss', () => {
  it('removes the candidate, records the id, and excludes it from the next query', async () => {
    enterQuietly();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9), cand('dict:bin', 'bin', 0.8)]) as never);
    await useRhymeStore.getState().runQuery();

    useRhymeStore.getState().dismiss('dict:tin');
    const s = useRhymeStore.getState();
    expect(s.candidates.map((c) => c.id)).toEqual(['dict:bin']);
    expect(s.dismissed.has('dict:tin')).toBe(true);

    rhymeSuggest.mockResolvedValueOnce(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().exclude_ids).toEqual(['dict:tin']);
  });
});

describe('excludeAnchor (chip removal)', () => {
  it('drops the excluded snippet from the anchors and the slot source', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['a', 'b']) });
    enterQuietly();
    useRhymeStore.getState().setTypedAnchor('tin');
    rhymeSuggest.mockResolvedValue(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().anchors).toEqual([
      { kind: 'snippet', snippet_id: 'a', line_index: null },
      { kind: 'snippet', snippet_id: 'b', line_index: null },
      { kind: 'text', text: 'tin' },
    ]);
    expect(lastRequest().slot).toEqual({ syllable_count: 4, stress: '1010' });

    useRhymeStore.getState().excludeAnchor('a');
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().anchors).toEqual([
      { kind: 'snippet', snippet_id: 'b', line_index: null },
      { kind: 'text', text: 'tin' },
    ]);
    expect(lastRequest().slot).toEqual({ syllable_count: 2, stress: '11' });
  });

  it('clears results without a request when every anchor is excluded', async () => {
    enterQuietly();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().excludeAnchor('a');
    rhymeSuggest.mockClear();
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest).not.toHaveBeenCalled();
    expect(useRhymeStore.getState().candidates).toEqual([]);
  });

  it('forgets an exclusion once the snippet leaves the selection', async () => {
    enterQuietly();
    useRhymeStore.getState().excludeAnchor('a');
    useCanvasStore.setState({ selectedSnippetIds: new Set(['b']) });
    rhymeSuggest.mockResolvedValue(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().excludedAnchorIds.size).toBe(0);
    useCanvasStore.setState({ selectedSnippetIds: new Set(['a', 'b']) });
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().anchors.map((a) => (a.kind === 'snippet' ? a.snippet_id : a.text))).toEqual(['a', 'b']);
  });

  it('restoreAnchor brings a chip back', async () => {
    enterQuietly();
    useRhymeStore.getState().excludeAnchor('a');
    useRhymeStore.getState().restoreAnchor('a');
    rhymeSuggest.mockResolvedValue(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().anchors).toEqual([{ kind: 'snippet', snippet_id: 'a', line_index: null }]);
  });
});

describe('materialize', () => {
  async function loadOne() {
    enterQuietly();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9), cand('dict:bin', 'bin', 0.8)]) as never);
    await useRhymeStore.getState().runQuery();
  }

  function derivedFrom(id: string, score: number) {
    return {
      provenance: 'dictionary',
      source_unit_id: id,
      score,
      match_depth: 1,
      k: 2,
      anchor_unit_id: 'note:a:0:0',
      anchor_text: 'hinge',
    };
  }

  it('creates a snippet to the right of the single anchor with derived_from, then removes the row', async () => {
    await loadOne();
    const created = { ...snippet('new1', 'tin', '1'), snippet_type: 'freestyle' as const };
    createSnippet.mockResolvedValueOnce({ data: created } as never);
    const pastBefore = useCanvasStore.getState().past.length;

    await useRhymeStore.getState().materialize('dict:tin');

    expect(createSnippet).toHaveBeenCalledTimes(1);
    expect(createSnippet).toHaveBeenCalledWith('c1', {
      raw_text: 'tin',
      position_x: 100 + 200 + MATERIALIZE_GAP_X,
      position_y: 100,
      snippet_type: 'freestyle',
      derived_from: derivedFrom('dict:tin', 0.9),
    });
    const s = useRhymeStore.getState();
    expect(s.candidates.map((c) => c.id)).toEqual(['dict:bin']);
    expect(s.dismissed.has('dict:tin')).toBe(true);
    expect(s.materializeCounts.get('a')).toBe(1);
    expect(useCanvasStore.getState().snippets.get('new1')).toEqual(created);
    expect(useCanvasStore.getState().past.length).toBe(pastBefore + 1);
    expect(s.error).toBeNull();
  });

  it('steps each further materialization from the same anchor down a row', async () => {
    await loadOne();
    createSnippet.mockResolvedValueOnce({ data: snippet('new1', 'tin', '1') } as never);
    createSnippet.mockResolvedValueOnce({ data: snippet('new2', 'bin', '1') } as never);
    await useRhymeStore.getState().materialize('dict:tin');
    await useRhymeStore.getState().materialize('dict:bin');
    expect(createSnippet.mock.calls[1][1].position_x).toBe(100 + 200 + MATERIALIZE_GAP_X);
    expect(createSnippet.mock.calls[1][1].position_y).toBe(100 + MATERIALIZE_STEP_Y);
    expect(useRhymeStore.getState().materializeCounts.get('a')).toBe(2);
  });

  it('places at the viewport centre for a typed-only anchor', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(), viewport: { x: -100, y: 50, zoom: 2 } });
    useRhymeStore.setState({ viewSize: { width: 800, height: 600 } });
    enterQuietly();
    useRhymeStore.getState().setTypedAnchor('hinge');
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    await useRhymeStore.getState().runQuery();
    createSnippet.mockResolvedValueOnce({ data: snippet('new3', 'tin', '1') } as never);
    await useRhymeStore.getState().materialize('dict:tin');
    const body = createSnippet.mock.calls[0][1];
    expect(body.position_x).toBe(300);
    expect(body.position_y).toBe(100);
    expect(useRhymeStore.getState().materializeCounts.size).toBe(0);
  });

  it('does not move the selection, so the anchor stays the query', async () => {
    await loadOne();
    createSnippet.mockResolvedValueOnce({ data: snippet('new1', 'tin', '1') } as never);
    await useRhymeStore.getState().materialize('dict:tin');
    expect(Array.from(useCanvasStore.getState().selectedSnippetIds)).toEqual(['a']);
  });

  it('stores a typed materialize error, keeps the row, and does not throw', async () => {
    await loadOne();
    createSnippet.mockRejectedValueOnce(axiosErr(500));
    await expect(useRhymeStore.getState().materialize('dict:tin')).resolves.toBeUndefined();
    const s = useRhymeStore.getState();
    expect(s.error?.kind).toBe('server');
    expect(s.error?.operation).toBe('materialize');
    expect(s.candidates.length).toBe(2);
    expect(s.materializeCounts.size).toBe(0);
    expect(useCanvasStore.getState().snippets.size).toBe(2);
  });

  it('ignores unknown ids', async () => {
    await loadOne();
    await useRhymeStore.getState().materialize('dict:nope');
    expect(createSnippet).not.toHaveBeenCalled();
  });
});

describe('canvasStore.addSnippetFromText', () => {
  it('posts and inserts the returned snippet with an undo snapshot', async () => {
    const created = snippet('n9', 'hello', '10');
    createSnippet.mockResolvedValueOnce({ data: created } as never);
    const result = await useCanvasStore.getState().addSnippetFromText('hello', 1, 2);
    expect(createSnippet).toHaveBeenCalledWith('c1', { raw_text: 'hello', position_x: 1, position_y: 2, snippet_type: 'freestyle', derived_from: undefined });
    expect(result).toEqual(created);
    expect(useCanvasStore.getState().snippets.get('n9')).toEqual(created);
    expect(useCanvasStore.getState().past.length).toBe(1);
  });

  it('returns null without a loaded canvas', async () => {
    useCanvasStore.setState({ canvasId: null });
    expect(await useCanvasStore.getState().addSnippetFromText('x', 0, 0)).toBeNull();
    expect(createSnippet).not.toHaveBeenCalled();
  });
});
