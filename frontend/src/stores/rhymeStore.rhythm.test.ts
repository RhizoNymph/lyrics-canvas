import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { nlpApi } from '@/api/client';
import { useRhymeStore } from './rhymeStore';
import { useCanvasStore } from './canvasStore';
import { useRhymeOrderStore } from './rhymeOrderStore';
import { currentRhythmFit, useRhymeRhythmFitStore } from './rhymeRhythmFitStore';
import { RHYTHM_FIT_STORAGE_KEY, RHYTHM_OFF } from '@/lib/rhyme/rhythmFit';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate, RhymeSuggestRequest, RhymeSuggestResponse, RhythmUnavailableReason } from '@/types/rhyme';
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
  const base = {
    id,
    canvas_id: 'c1',
    placement_id: `p-${id}`,
    canvases: [{ id: 'c1', title: 'Canvas' }],
    snippet_type: 'verse' as const,
    grid_mode: 'auto' as const,
    beats_per_line: null,
    grid_data: { lines: [{ cells: [{ syllable: 'x', stress: 1 as const }], syllable_count: 1, raw_text }] },
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
  return Object.assign(base, { score: null, score_view: false });
}

function cand(id: string, score: number, band: RhymeBand, rhythm_fit: number | null): RhymeCandidate {
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
    similarity: null,
    rhythm_fit,
  };
}

interface Answer {
  order_applied?: RhymeOrder;
  rhythm_applied?: boolean;
  rhythm_unavailable_reason?: RhythmUnavailableReason | null;
}

function response(candidates: RhymeCandidate[], total: number, answer: Answer = {}): { data: RhymeSuggestResponse } {
  return { data: { anchors: [], candidates, total, elapsed_ms: 1, ...answer } };
}

const lastRequest = (): RhymeSuggestRequest => rhymeSuggest.mock.calls[rhymeSuggest.mock.calls.length - 1][0];

let data: Record<string, string>;

beforeEach(() => {
  vi.clearAllMocks();
  data = {};
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in data ? data[k] : null),
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
  });
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([['a', snippet('a', 'orange door hinge')]]),
    selectedSnippetIds: new Set(['a']),
    viewport: { x: 0, y: 0, zoom: 1 },
    past: [],
    future: [],
  });
  useRhymeOrderStore.setState({ order: 'rhyme' });
  useRhymeRhythmFitStore.setState({ on: false });
  useRhymeStore.getState().exit();
  useRhymeStore.setState({
    active: true,
    error: null,
    k: 2,
    minScore: 0.5,
    sources: { notes: true, dictionary: true },
    slotMode: 'from-selection',
    slot: null,
    includeRare: false,
    tagFilterText: '',
    typedAnchor: '',
  });
});

afterEach(() => vi.unstubAllGlobals());

describe('rhymeRhythmFitStore', () => {
  it('starts off and remembers a change per viewer', () => {
    expect(currentRhythmFit()).toBe(false);
    useRhymeRhythmFitStore.getState().setOn(true);
    expect(currentRhythmFit()).toBe(true);
    expect(data[RHYTHM_FIT_STORAGE_KEY]).toBe('on');
  });

  it('keeps the switch in memory when storage refuses the write', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
    });
    useRhymeRhythmFitStore.getState().setOn(true);
    expect(currentRhythmFit()).toBe(true);
  });
});

describe('fit rhythm in the sidebar query', () => {
  it('sends nothing while the switch is off', async () => {
    rhymeSuggest.mockResolvedValueOnce(response([], 0) as never);
    await useRhymeStore.getState().runQuery();
    expect('rhythm_fit' in lastRequest()).toBe(false);
    expect(useRhymeStore.getState().rhythm).toEqual(RHYTHM_OFF);
  });

  it('sends rhythm_fit for a snippet anchor and keeps the applied answer', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    rhymeSuggest.mockResolvedValueOnce(
      response([cand('dict:a', 0.97, 'perfect', 1), cand('dict:b', 0.96, 'perfect', 0.5)], 2, { rhythm_applied: true }) as never,
    );
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().rhythm_fit).toBe(true);
    const s = useRhymeStore.getState();
    expect(s.rhythm).toEqual({ requested: true, applied: true, unavailable: null });
    expect(s.candidates.map((c) => c.rhythm_fit)).toEqual([1, 0.5]);
  });

  it('does not send it for a typed anchor alone', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    useCanvasStore.setState({ selectedSnippetIds: new Set() });
    useRhymeStore.setState({ typedAnchor: 'door hinge' });
    rhymeSuggest.mockResolvedValueOnce(response([], 0) as never);
    await useRhymeStore.getState().runQuery();
    expect('rhythm_fit' in lastRequest()).toBe(false);
    expect(useRhymeStore.getState().rhythm).toEqual(RHYTHM_OFF);
  });

  it('does not send it when the only snippet anchor is excluded', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    useRhymeStore.setState({ typedAnchor: 'door hinge', excludedAnchorIds: new Set(['a']) });
    rhymeSuggest.mockResolvedValueOnce(response([], 0) as never);
    await useRhymeStore.getState().runQuery();
    expect('rhythm_fit' in lastRequest()).toBe(false);
  });

  it.each(['no_score', 'slot_unplaced'] as const)('records the unavailable reason %s', async (reason) => {
    useRhymeRhythmFitStore.setState({ on: true });
    rhymeSuggest.mockResolvedValueOnce(
      response([cand('dict:a', 0.97, 'perfect', null)], 1, { rhythm_applied: false, rhythm_unavailable_reason: reason }) as never,
    );
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().rhythm).toEqual({ requested: true, applied: false, unavailable: reason });
  });

  it('works together with meaning order', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    useRhymeOrderStore.setState({ order: 'meaning' });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:a', 0.97, 'perfect', 1)], 1, { order_applied: 'meaning', rhythm_applied: true }) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().order).toBe('meaning');
    expect(lastRequest().rhythm_fit).toBe(true);
    const s = useRhymeStore.getState();
    expect(s.ordering).toEqual({ requested: 'meaning', applied: 'meaning' });
    expect(s.rhythm.applied).toBe(true);
  });

  it('pages with the rhythm choice of the loaded results, even after the switch moved', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    const page1 = Array.from({ length: RHYME_PAGE_SIZE }, (_, i) => cand(`dict:p1-${i}`, 0.9, 'strong', 0.5));
    rhymeSuggest.mockResolvedValueOnce(response(page1, 100, { rhythm_applied: true }) as never);
    await useRhymeStore.getState().runQuery();

    useRhymeRhythmFitStore.setState({ on: false });
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:p2', 0.89, 'strong', 0.4)], 100, { rhythm_applied: true }) as never);
    await useRhymeStore.getState().loadMore();
    expect(lastRequest().rhythm_fit).toBe(true);
    expect(lastRequest().offset).toBe(RHYME_PAGE_SIZE);
    expect(useRhymeStore.getState().rhythm.applied).toBe(true);
    expect(useRhymeStore.getState().candidates).toHaveLength(RHYME_PAGE_SIZE + 1);
  });

  it('a page without rhythm keeps sending nothing', async () => {
    const page1 = Array.from({ length: RHYME_PAGE_SIZE }, (_, i) => cand(`dict:p1-${i}`, 0.9, 'strong', null));
    rhymeSuggest.mockResolvedValueOnce(response(page1, 100) as never);
    await useRhymeStore.getState().runQuery();
    useRhymeRhythmFitStore.setState({ on: true });
    rhymeSuggest.mockResolvedValueOnce(response([], 100) as never);
    await useRhymeStore.getState().loadMore();
    expect('rhythm_fit' in lastRequest()).toBe(false);
  });

  it('exit and an empty query reset the rhythm state', async () => {
    useRhymeRhythmFitStore.setState({ on: true });
    rhymeSuggest.mockResolvedValueOnce(response([], 0, { rhythm_applied: true }) as never);
    await useRhymeStore.getState().runQuery();
    useCanvasStore.setState({ selectedSnippetIds: new Set() });
    await useRhymeStore.getState().runQuery();
    expect(useRhymeStore.getState().rhythm).toEqual(RHYTHM_OFF);

    useCanvasStore.setState({ selectedSnippetIds: new Set(['a']) });
    rhymeSuggest.mockResolvedValueOnce(response([], 0, { rhythm_applied: true }) as never);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().exit();
    expect(useRhymeStore.getState().rhythm).toEqual(RHYTHM_OFF);
  });
});
