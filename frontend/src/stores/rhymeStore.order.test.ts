import { describe, it, expect, vi, beforeEach } from 'vitest';
import { nlpApi } from '@/api/client';
import { useRhymeStore } from './rhymeStore';
import { useCanvasStore } from './canvasStore';
import { useRhymeOrderStore } from './rhymeOrderStore';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate, RhymeSuggestRequest, RhymeSuggestResponse } from '@/types/rhyme';
import type { RhymeBand, RhymeOrder } from '@/lib/rhyme/order';
import type { Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const rhymeSuggest = vi.mocked(nlpApi.rhymeSuggest);

function snippet(id: string, raw_text: string): Snippet {
  return {
    id,
    canvas_id: 'c1',
    placement_id: `p-${id}`,
    canvases: [{ id: 'c1', title: 'Canvas' }],
    snippet_type: 'verse',
    grid_mode: 'auto',
    beats_per_line: null,
    grid_data: { lines: [{ cells: [{ syllable: 'x', stress: 1 }], syllable_count: 1, raw_text }] },
    raw_text,
    position_x: 0,
    position_y: 0,
    width: 200,
    height: 100,
    z_index: 0,
    list_rank: 'a0',
    group_id: null,
    tags: [],
    created_at: '',
    updated_at: '',
    content_updated_at: '',
  };
}

function cand(id: string, score: number, band: RhymeBand, similarity: number | null): RhymeCandidate {
  return {
    id,
    text: id,
    provenance: 'dictionary',
    ephemeral: true,
    syllable_count: 1,
    stress: '1',
    rhyme_key: ['IH', 'N'],
    syllables: [],
    grid_line: { cells: [], syllable_count: 1, raw_text: id },
    tags: [],
    score,
    match_depth: 1,
    best_anchor_id: 'note:a:0:0',
    fits_slot: true,
    alignment: [],
    source: 'dictionary',
    band,
    similarity,
  };
}

function response(candidates: RhymeCandidate[], total: number, order_applied?: RhymeOrder): { data: RhymeSuggestResponse } {
  return { data: { anchors: [], candidates, total, elapsed_ms: 1, ...(order_applied === undefined ? {} : { order_applied }) } };
}

const lastRequest = (): RhymeSuggestRequest => rhymeSuggest.mock.calls[rhymeSuggest.mock.calls.length - 1][0];

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([['a', snippet('a', 'orange door hinge')]]),
    selectedSnippetIds: new Set(['a']),
    viewport: { x: 0, y: 0, zoom: 1 },
    past: [],
    future: [],
  });
  useRhymeOrderStore.setState({ order: 'rhyme' });
  useRhymeStore.getState().exit();
  useRhymeStore.setState({ active: true, error: null, k: 2, minScore: 0.5, sources: { notes: true, dictionary: true }, slotMode: 'from-selection', slot: null, includeRare: false, tagFilterText: '' });
});

describe('rhyme order in the sidebar query', () => {
  it('sends no order field in rhyme order', async () => {
    rhymeSuggest.mockResolvedValueOnce(response([], 0, 'rhyme') as never);
    await useRhymeStore.getState().runQuery();
    expect('order' in lastRequest()).toBe(false);
    expect(useRhymeStore.getState().ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });

  it('sends order "meaning" and keeps the applied order with the results', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:a', 0.97, 'perfect', 0.6), cand('dict:b', 0.9, 'strong', 0.8)], 2, 'meaning') as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().order).toBe('meaning');
    expect(lastRequest().offset).toBe(0);
    const s = useRhymeStore.getState();
    expect(s.ordering).toEqual({ requested: 'meaning', applied: 'meaning' });
    expect(s.candidates.map((c) => [c.band, c.similarity])).toEqual([['perfect', 0.6], ['strong', 0.8]]);
  });

  it('records the fallback when the server answers in rhyme order', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:a', 0.97, 'perfect', null)], 1, 'rhyme') as never);
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().ordering).toEqual({ requested: 'meaning', applied: 'rhyme' });
  });

  it('treats a response without order_applied as rhyme order', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([], 0) as never);
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().ordering.applied).toBe('rhyme');
  });

  it('pages in the order of the loaded results, even after the toggle moved', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    const page1 = Array.from({ length: RHYME_PAGE_SIZE }, (_, i) => cand(`dict:p1-${i}`, 0.9, 'strong', 0.5));
    rhymeSuggest.mockResolvedValueOnce(response(page1, 100, 'meaning') as never);
    await useRhymeStore.getState().runQuery();

    useRhymeOrderStore.setState({ order: 'rhyme' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:p2', 0.89, 'strong', 0.4)], 100, 'meaning') as never);
    await useRhymeStore.getState().loadMore();
    expect(lastRequest().order).toBe('meaning');
    expect(lastRequest().offset).toBe(RHYME_PAGE_SIZE);
    expect(useRhymeStore.getState().ordering).toEqual({ requested: 'meaning', applied: 'meaning' });
    expect(useRhymeStore.getState().candidates).toHaveLength(RHYME_PAGE_SIZE + 1);
  });

  it('a new query after a toggle starts from the first page in the new order', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:a', 0.9, 'strong', 0.5)], 100, 'meaning') as never);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.setState({ offset: RHYME_PAGE_SIZE });

    useRhymeOrderStore.setState({ order: 'rhyme' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:b', 0.9, 'strong', null)], 100, 'rhyme') as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().offset).toBe(0);
    expect('order' in lastRequest()).toBe(false);
    const s = useRhymeStore.getState();
    expect(s.offset).toBe(0);
    expect(s.candidates.map((c) => c.id)).toEqual(['dict:b']);
    expect(s.ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });

  it('exit resets the ordering', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([], 0, 'meaning') as never);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().exit();
    expect(useRhymeStore.getState().ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });

  it('a query with nothing to ask clears the ordering', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([], 0, 'meaning') as never);
    await useRhymeStore.getState().runQuery();
    useCanvasStore.setState({ selectedSnippetIds: new Set() });
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });
});
