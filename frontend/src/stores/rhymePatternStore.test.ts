import { describe, it, expect, vi, beforeEach } from 'vitest';
import { rhymePatternApi, snippetApi } from '@/api/client';
import { useRhymePatternStore, trayOf } from './rhymePatternStore';
import type { ReadyView } from './rhymePatternStore';
import { useCanvasStore } from './canvasStore';
import { useRhymeStore } from './rhymeStore';
import { useWordBankStore } from './wordBankStore';
import { PatternError } from '@/lib/rhyme/pattern';
import { pcand, phit, pline, pmatch, pslot } from '@/lib/rhyme/pattern/fixtures';
import { MATERIALIZE_GAP_X, MATERIALIZE_STEP_Y } from '@/lib/rhyme/materializePosition';
import type { PatternDetectResponse, PatternSuggestRequest, PatternSuggestResponse } from '@/types/rhymePattern';
import type { Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  rhymePatternApi: { detect: vi.fn(), suggest: vi.fn() },
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  wordBankApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const detect = vi.mocked(rhymePatternApi.detect);
const suggest = vi.mocked(rhymePatternApi.suggest);
const createSnippet = vi.mocked(snippetApi.create);

const store = () => useRhymePatternStore.getState();
const view = (): ReadyView => {
  const v = store().view;
  if (v.kind !== 'ready') throw new Error(`expected ready, got ${v.kind}`);
  return v;
};

function anchorSnippet(): Snippet {
  return {
    id: 'sn',
    canvas_id: 'c1',
    placement_id: 'p-sn',
    canvases: [{ id: 'c1', title: 'Canvas' }],
    snippet_type: 'verse',
    grid_mode: 'auto',
    beats_per_line: null,
    grid_data: null,
    raw_text: 'x',
    position_x: 100,
    position_y: 50,
    width: 200,
    height: 100,
    z_index: 0,
    group_id: null,
    tags: [],
    created_at: '',
    updated_at: '',
    content_updated_at: '',
  } as unknown as Snippet;
}

const detected: PatternDetectResponse = {
  lines: [pline(0, 'steady when the pressure on'), pline(1, 'already heavy weather wrong'), pline(2, 'ready or not better long')],
  slots: [
    pslot('s0', [[0, 0, 1], [1, 1, 2], [2, 0, 1]]),
    pslot('s1', [[0, 4, 5], [1, 3, 4], [2, 4, 5]], { default_on: false }),
  ],
  elapsed_ms: 1,
};

function suggestBody(over: Partial<PatternSuggestResponse> = {}): PatternSuggestResponse {
  return {
    slots: [{ slot: detected.slots[0], candidates: [pcand('dict:a', 'steady', 0.9), pcand('dict:b', 'heady', 0.8)], total: 45 }],
    line_matches: [pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready'), phit('s1', 2, 3, 'strong')], 0.77)],
    line_match_total: 1,
    elapsed_ms: 1,
    ...over,
  };
}

function lastSuggest(): PatternSuggestRequest {
  return suggest.mock.calls[suggest.mock.calls.length - 1][0];
}

async function enterReady() {
  detect.mockResolvedValueOnce(detected);
  suggest.mockResolvedValueOnce(suggestBody());
  store().enter('sn', { lineStart: 0, lineEnd: 3 });
  await vi.waitFor(() => expect(store().suggest.kind).toBe('done'));
}

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([['sn', anchorSnippet()]]),
    selectedSnippetIds: new Set(['sn']),
    viewport: { x: 0, y: 0, zoom: 1 },
    past: [],
    future: [],
  });
  useRhymeStore.setState({ minScore: 0.5, sources: { notes: true, dictionary: true }, includeRare: false, tagFilterText: '' });
  store().exit();
  useRhymePatternStore.setState({ detectMinScore: 0.75 });
});

describe('enter', () => {
  it('detects the range, builds the edit state, and searches the on slots', async () => {
    await enterReady();
    expect(detect).toHaveBeenCalledWith({ canvas_id: 'c1', snippet_id: 'sn', line_start: 0, line_end: 3, weights: null, dialect: null, min_score: 0.75 });
    const v = view();
    expect(v.edit.slots.map((s) => [s.id, s.on])).toEqual([['s0', true], ['s1', false]]);
    expect(lastSuggest().slots.map((s) => s.id)).toEqual(['s0']);
    expect(lastSuggest().offsets).toEqual({});
    expect(v.results?.columns.get('s0')?.candidates).toHaveLength(2);
    expect(v.results?.lineMatches).toHaveLength(1);
  });

  it('shows a typed detect failure', async () => {
    detect.mockRejectedValueOnce(new PatternError('range_invalid', 'detect', 'bad range', 422));
    store().enter('sn', { lineStart: 0, lineEnd: 3 });
    await vi.waitFor(() => expect(store().view.kind).toBe('detect_failed'));
    const v = store().view;
    expect(v.kind === 'detect_failed' && v.error.kind).toBe('range_invalid');
    expect(suggest).not.toHaveBeenCalled();
  });

  it('does nothing without an open canvas', () => {
    useCanvasStore.setState({ canvasId: null });
    store().enter('sn', { lineStart: 0, lineEnd: 3 });
    expect(store().view.kind).toBe('off');
    expect(detect).not.toHaveBeenCalled();
  });

  it('with no detected slots, stays ready and asks nothing', async () => {
    detect.mockResolvedValueOnce({ ...detected, slots: [] });
    store().enter('sn', { lineStart: 0, lineEnd: 3 });
    await vi.waitFor(() => expect(store().view.kind).toBe('ready'));
    expect(store().suggest.kind).toBe('idle');
    expect(suggest).not.toHaveBeenCalled();
  });
});

describe('exit and reset', () => {
  it('exit drops the session and ignores a late detect answer', async () => {
    let resolve: (r: PatternDetectResponse) => void = () => undefined;
    detect.mockReturnValueOnce(new Promise((r) => (resolve = r)));
    store().enter('sn', { lineStart: 0, lineEnd: 3 });
    store().exit();
    resolve(detected);
    await Promise.resolve();
    expect(store().view.kind).toBe('off');
  });

  it('reset re-detects and discards edits', async () => {
    await enterReady();
    store().edit({ type: 'drop', slotId: 's0' });
    detect.mockResolvedValueOnce(detected);
    suggest.mockResolvedValueOnce(suggestBody());
    store().reset();
    await vi.waitFor(() => expect(store().view.kind).toBe('ready'));
    expect(view().edit.slots.map((s) => s.id)).toEqual(['s0', 's1']);
  });
});

describe('edit', () => {
  it('applies an accepted edit and prunes picks of dropped slots', async () => {
    await enterReady();
    store().togglePick('s0', pcand('dict:a', 'steady', 0.9));
    store().edit({ type: 'drop', slotId: 's0' });
    expect(view().edit.slots.map((s) => s.id)).toEqual(['s1']);
    expect(view().picks.size).toBe(0);
    expect(store().notice).toBeNull();
  });

  it('refuses overlapping and out-of-order edits with a message and no change', async () => {
    await enterReady();
    const before = view().edit;
    store().edit({ type: 'add', slotId: 's1', line: 0, word: 0 });
    expect(view().edit).toBe(before);
    expect(store().notice).toBe('Those words already belong to slot A.');
    store().edit({ type: 'new_slot', line: 0, word: 2 });
    expect(store().notice).toBeNull();
    const withNew = view().edit;
    // u0 (slot B) sits after s0 on line 0; word 0 of line 1 comes before s0's member there.
    store().edit({ type: 'add', slotId: 'u0', line: 1, word: 0 });
    expect(view().edit).toBe(withNew);
    expect(store().notice).toBe('That would put slot B out of order on line 2.');
  });

  it('new slots get u ids and are sent on the next search', async () => {
    await enterReady();
    store().edit({ type: 'new_slot', line: 0, word: 2 });
    expect(view().edit.slots.map((s) => s.id)).toEqual(['s0', 'u0', 's1']);
    suggest.mockResolvedValueOnce(suggestBody());
    await store().runSuggest();
    expect(lastSuggest().slots.map((s) => s.id)).toEqual(['s0', 'u0']);
  });
});

describe('runSuggest', () => {
  it('is idle when every slot is off', async () => {
    await enterReady();
    store().edit({ type: 'toggle', slotId: 's0' });
    suggest.mockClear();
    await store().runSuggest();
    expect(suggest).not.toHaveBeenCalled();
    expect(store().suggest.kind).toBe('idle');
    expect(view().results).toBeNull();
  });

  it('blocks an invalid tag filter locally', async () => {
    await enterReady();
    useRhymeStore.setState({ tagFilterText: 'a &' });
    suggest.mockClear();
    await store().runSuggest();
    expect(suggest).not.toHaveBeenCalled();
    const s = store().suggest;
    expect(s.kind === 'failed' && s.error.kind).toBe('invalid_tag_filter');
  });

  it('sends the shared controls, the normalized tag filter and the dismissed ids', async () => {
    await enterReady();
    useRhymeStore.setState({ minScore: 0.6, sources: { notes: false, dictionary: true }, includeRare: true, tagFilterText: 'love' });
    store().dismiss('dict:b');
    suggest.mockResolvedValueOnce(suggestBody());
    await store().runSuggest();
    expect(lastSuggest()).toMatchObject({ min_score: 0.6, sources: ['dictionary'], include_rare: true, tag_filter: 'love', exclude_ids: ['dict:b'] });
  });

  it('ignores an older answer that arrives after a newer one', async () => {
    await enterReady();
    let resolveOld: (r: PatternSuggestResponse) => void = () => undefined;
    suggest.mockReturnValueOnce(new Promise((r) => (resolveOld = r)));
    const old = store().runSuggest();
    suggest.mockResolvedValueOnce(suggestBody({ slots: [{ slot: detected.slots[0], candidates: [pcand('dict:new', 'new', 0.9)], total: 1 }] }));
    await store().runSuggest();
    resolveOld(suggestBody({ slots: [{ slot: detected.slots[0], candidates: [pcand('dict:old', 'old', 0.9)], total: 1 }] }));
    await old;
    expect(view().results?.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:new']);
  });

  it('records a server failure and clears the results', async () => {
    await enterReady();
    suggest.mockRejectedValueOnce(new PatternError('slot_order', 'suggest', 'order', 422));
    await store().runSuggest();
    const s = store().suggest;
    expect(s.kind === 'failed' && s.error.kind).toBe('slot_order');
    expect(view().results).toBeNull();
  });
});

describe('paging', () => {
  it('fetches one slot page through offsets and appends it', async () => {
    await enterReady();
    suggest.mockResolvedValueOnce(suggestBody({ slots: [{ slot: detected.slots[0], candidates: [pcand('dict:c', 'ready', 0.7)], total: 45 }] }));
    await store().loadMoreSlot('s0');
    expect(lastSuggest()).toMatchObject({ offsets: { s0: 30 }, line_matches: false });
    expect(suggest.mock.calls[suggest.mock.calls.length - 1][1]).toBe('more');
    expect(view().results?.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:a', 'dict:b', 'dict:c']);
    expect(store().paging.size).toBe(0);
  });

  it('does not page past the total', async () => {
    await enterReady();
    suggest.mockClear();
    await store().loadMoreLines();
    expect(suggest).not.toHaveBeenCalled();
  });

  it('reports a paging failure as an action error', async () => {
    await enterReady();
    suggest.mockRejectedValueOnce(new PatternError('server', 'more', 'x', 500));
    await store().loadMoreSlot('s0');
    expect(store().actionError?.kind).toBe('server');
  });
});

describe('dismiss and picks', () => {
  it('dismiss removes the row everywhere and its pick', async () => {
    await enterReady();
    store().togglePick('s0', pcand('dict:a', 'steady', 0.9));
    store().dismiss('dict:a');
    expect(view().results?.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:b']);
    expect(view().picks.size).toBe(0);
    expect([...store().dismissed]).toEqual(['dict:a']);
  });

  it('tray shows the on slots with picks and gaps', async () => {
    await enterReady();
    store().edit({ type: 'toggle', slotId: 's1' });
    store().togglePick('s1', pcand('dict:z', 'strong', 0.9));
    expect(trayOf(view())).toEqual([
      { slotId: 's0', letter: 'A', text: null },
      { slotId: 's1', letter: 'B', text: 'strong' },
    ]);
    store().clearPicks();
    expect(view().picks.size).toBe(0);
  });
});

describe('makeSnippet', () => {
  it('creates one line of the picks next to the anchor, stepping down per snippet, without derived_from', async () => {
    await enterReady();
    store().edit({ type: 'toggle', slotId: 's1' });
    store().togglePick('s0', pcand('dict:a', 'steady', 0.9));
    store().togglePick('s1', pcand('dict:z', 'strong', 0.9));
    createSnippet.mockResolvedValue({ data: { ...anchorSnippet(), id: 'new' } } as never);
    await store().makeSnippet();
    await store().makeSnippet();
    expect(createSnippet).toHaveBeenCalledTimes(2);
    const [canvasId, first] = createSnippet.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect(first).toMatchObject({ raw_text: 'steady strong', position_x: 100 + 200 + MATERIALIZE_GAP_X, position_y: 50 });
    expect(first.derived_from).toBeUndefined();
    expect(createSnippet.mock.calls[1][1]).toMatchObject({ position_y: 50 + MATERIALIZE_STEP_Y });
  });

  it('does nothing without picks', async () => {
    await enterReady();
    await store().makeSnippet();
    expect(createSnippet).not.toHaveBeenCalled();
  });

  it('reports a create failure', async () => {
    await enterReady();
    store().togglePick('s0', pcand('dict:a', 'steady', 0.9));
    createSnippet.mockRejectedValueOnce(new Error('boom'));
    await store().makeSnippet();
    expect(store().actionError).toMatchObject({ kind: 'unknown', operation: 'make_snippet' });
  });
});

describe('addPicksToBank', () => {
  it('adds the picks as one comma-separated batch', async () => {
    await enterReady();
    const addFromText = vi.fn().mockResolvedValue({ ok: true, value: { created: [], existing: [] } });
    useWordBankStore.setState({ addFromText });
    store().edit({ type: 'toggle', slotId: 's1' });
    store().togglePick('s0', pcand('dict:a', 'steady', 0.9));
    store().togglePick('s1', pcand('dict:z', 'so strong', 0.9));
    await store().addPicksToBank();
    expect(addFromText).toHaveBeenCalledWith('steady, so strong', 'rhyme');
  });
});

describe('materializeLine', () => {
  it('creates the note line with derived_from and removes the row', async () => {
    await enterReady();
    createSnippet.mockResolvedValueOnce({ data: { ...anchorSnippet(), id: 'new' } } as never);
    await store().materializeLine('note:n:0:0');
    expect(createSnippet.mock.calls[0][1]).toMatchObject({
      raw_text: 'ready and strong',
      derived_from: { provenance: 'note', source_unit_id: 'note:n:0:0', score: 0.77, k: 2, anchor_unit_id: 'pattern:sn:2:0-1' },
    });
    expect(view().results?.lineMatches).toEqual([]);
    expect(store().createdCount).toBe(1);
  });
});
