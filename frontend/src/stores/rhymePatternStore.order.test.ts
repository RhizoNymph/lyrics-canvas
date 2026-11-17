import { describe, it, expect, vi, beforeEach } from 'vitest';
import { rhymePatternApi } from '@/api/client';
import { useRhymePatternStore } from './rhymePatternStore';
import type { ReadyView } from './rhymePatternStore';
import { useCanvasStore } from './canvasStore';
import { useRhymeStore } from './rhymeStore';
import { useRhymeOrderStore } from './rhymeOrderStore';
import { pcand, phit, pline, pmatch, pslot } from '@/lib/rhyme/pattern/fixtures';
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
const store = () => useRhymePatternStore.getState();
const view = (): ReadyView => {
  const v = store().view;
  if (v.kind !== 'ready') throw new Error(`expected ready, got ${v.kind}`);
  return v;
};

const detected: PatternDetectResponse = {
  lines: [pline(0, 'steady when the pressure on'), pline(1, 'already heavy weather wrong')],
  slots: [pslot('s0', [[0, 0, 1], [1, 1, 2]])],
  elapsed_ms: 1,
};

function body(over: Partial<PatternSuggestResponse> = {}): PatternSuggestResponse {
  return {
    slots: [{ slot: detected.slots[0], candidates: [pcand('dict:a', 'steady', 0.97, { band: 'perfect', similarity: 0.3 }), pcand('dict:b', 'heady', 0.9, { band: 'strong', similarity: 0.7 })], total: 45 }],
    line_matches: [pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready')], 0.77)],
    line_match_total: 40,
    elapsed_ms: 1,
    ...over,
  };
}

const lastSuggest = (): PatternSuggestRequest => suggest.mock.calls[suggest.mock.calls.length - 1][0];

async function enterReady(first: PatternSuggestResponse) {
  detect.mockResolvedValueOnce(detected);
  suggest.mockResolvedValueOnce(first);
  store().enter('sn', { lineStart: 0, lineEnd: 2 });
  await vi.waitFor(() => expect(store().suggest.kind).toBe('done'));
}

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([['sn', { id: 'sn', raw_text: 'x', position_x: 0, position_y: 0, width: 200, height: 100 } as unknown as Snippet]]),
    selectedSnippetIds: new Set(['sn']),
    viewport: { x: 0, y: 0, zoom: 1 },
  });
  useRhymeStore.setState({ minScore: 0.5, sources: { notes: true, dictionary: true }, includeRare: false, tagFilterText: '' });
  useRhymeOrderStore.setState({ order: 'rhyme' });
  store().exit();
});

describe('pattern suggest order', () => {
  it('sends no order in rhyme order', async () => {
    await enterReady(body({ order_applied: 'rhyme' }));
    expect('order' in lastSuggest()).toBe(false);
    expect(view().results?.ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });

  it('sends order "meaning" and keeps the applied order with the columns', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    await enterReady(body({ order_applied: 'meaning' }));
    expect(lastSuggest().order).toBe('meaning');
    expect(view().results?.ordering).toEqual({ requested: 'meaning', applied: 'meaning' });
    expect(view().results?.columns.get('s0')?.candidates.map((c) => c.band)).toEqual(['perfect', 'strong']);
  });

  it('records the fallback when meaning was asked but rhyme came back', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    await enterReady(body({ order_applied: 'rhyme' }));
    expect(view().results?.ordering).toEqual({ requested: 'meaning', applied: 'rhyme' });
  });

  it('a slot page keeps the order of its column even after the toggle moved', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    await enterReady(body({ order_applied: 'meaning' }));
    useRhymeOrderStore.setState({ order: 'rhyme' });
    suggest.mockResolvedValueOnce(body({ order_applied: 'meaning', slots: [{ slot: detected.slots[0], candidates: [pcand('dict:c', 'ready', 0.88, { band: 'strong', similarity: 0.5 })], total: 45 }] }));
    await store().loadMoreSlot('s0');
    expect(lastSuggest()).toMatchObject({ order: 'meaning', offsets: { s0: 30 } });
    expect(view().results?.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:a', 'dict:b', 'dict:c']);
    expect(view().results?.ordering.requested).toBe('meaning');
  });

  it('a line-match page keeps the order too', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    await enterReady(body({ order_applied: 'meaning' }));
    useRhymeOrderStore.setState({ order: 'rhyme' });
    suggest.mockResolvedValueOnce(body({ line_matches: [pmatch('note:n:1:0', 'heavy song', [phit('s0', 0, 1, 'heavy')], 0.7)] }));
    await store().loadMoreLines();
    expect(lastSuggest()).toMatchObject({ order: 'meaning', line_match_offset: 30 });
  });

  it('a new first page after the toggle uses the new order', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    await enterReady(body({ order_applied: 'meaning' }));
    useRhymeOrderStore.setState({ order: 'rhyme' });
    suggest.mockResolvedValueOnce(body({ order_applied: 'rhyme' }));
    await store().runSuggest();
    expect('order' in lastSuggest()).toBe(false);
    expect(lastSuggest().offsets).toEqual({});
    expect(view().results?.ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });
});
