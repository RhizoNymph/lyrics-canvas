import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { nlpApi, snippetApi } from '@/api/client';
import { useRhymeStore } from './rhymeStore';
import { useCanvasStore } from './canvasStore';
import { RhymeError, RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate, RhymeUnit, RhymeSuggestResponse, RhymeSuggestRequest } from '@/types/rhyme';
import type { Snippet } from '@/types';
import { candidateCardSize, candidateTopLeft } from '@/lib/rhyme/candidateGeometry';

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
  useRhymeStore.setState({ k: 2, minScore: 0.5, sources: { notes: true, dictionary: true }, slotMode: 'from-selection', slot: null });
});

describe('enter / exit', () => {
  it('enter activates; exit clears candidate state and deactivates but keeps params', async () => {
    const s = useRhymeStore.getState();
    s.enter();
    expect(useRhymeStore.getState().active).toBe(true);
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    useRhymeStore.getState().setTypedAnchor('door');
    useRhymeStore.getState().setK(3);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().dismiss('dict:tin');
    useRhymeStore.getState().setHovered('dict:tin');
    expect(useRhymeStore.getState().dismissed.size).toBe(1);

    useRhymeStore.getState().exit();
    const after = useRhymeStore.getState();
    expect(after.active).toBe(false);
    expect(after.candidates).toEqual([]);
    expect(after.anchors).toEqual([]);
    expect(after.positions.size).toBe(0);
    expect(after.dismissed.size).toBe(0);
    expect(after.typedAnchor).toBe('');
    expect(after.hoveredId).toBeNull();
    expect(after.pinnedId).toBeNull();
    expect(after.total).toBe(0);
    expect(after.offset).toBe(0);
    expect(after.error).toBeNull();
    expect(after.loading).toBe(false);
    expect(after.k).toBe(3);
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
    useRhymeStore.getState().enter();
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
    expect(s.positions.has('dict:tin')).toBe(true);
    expect(s.positions.has('note:b:0:1')).toBe(true);
    expect(s.slot).toEqual({ syllable_count: 4, stress: '1010' });
    expect(s.loading).toBe(false);
    expect(s.error).toBeNull();
  });

  it('uses the manual slot when slotMode is manual, and null when unset', async () => {
    useRhymeStore.getState().enter();
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
    useRhymeStore.getState().enter();
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
    useRhymeStore.getState().enter();
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

  it('keeps positions stable for ids seen before and places new ids', async () => {
    useRhymeStore.getState().enter();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9), cand('dict:bin', 'bin', 0.8)]) as never);
    await useRhymeStore.getState().runQuery();
    const tin = useRhymeStore.getState().positions.get('dict:tin');
    const bin = useRhymeStore.getState().positions.get('dict:bin');

    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:bin', 'bin', 0.3), cand('dict:fin', 'fin', 0.95), cand('dict:tin', 'tin', 0.1)]) as never);
    await useRhymeStore.getState().runQuery();
    const p = useRhymeStore.getState().positions;
    expect(p.get('dict:tin')).toEqual(tin);
    expect(p.get('dict:bin')).toEqual(bin);
    expect(p.has('dict:fin')).toBe(true);
  });

  it('stores a typed error and does not throw', async () => {
    useRhymeStore.getState().enter();
    rhymeSuggest.mockRejectedValueOnce(axiosErr(422));
    await expect(useRhymeStore.getState().runQuery()).resolves.toBeUndefined();
    const s = useRhymeStore.getState();
    expect(s.error).toBeInstanceOf(RhymeError);
    expect(s.error?.kind).toBe('invalid_request');
    expect(s.error?.operation).toBe('query');
    expect(s.loading).toBe(false);
  });

  it('ignores a stale response that resolves after a newer query', async () => {
    useRhymeStore.getState().enter();
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
    useRhymeStore.getState().enter();
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
    expect(s.positions.has('dict:extra')).toBe(true);
  });

  it('does nothing when everything is already loaded', async () => {
    useRhymeStore.getState().enter();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)], 1) as never);
    await useRhymeStore.getState().runQuery();
    rhymeSuggest.mockClear();
    await useRhymeStore.getState().loadMore();
    expect(rhymeSuggest).not.toHaveBeenCalled();
  });
});

describe('dismiss', () => {
  it('removes the candidate, records the id, and excludes it from the next query', async () => {
    useRhymeStore.getState().enter();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9), cand('dict:bin', 'bin', 0.8)]) as never);
    await useRhymeStore.getState().runQuery();
    useRhymeStore.getState().setHovered('dict:tin');
    useRhymeStore.getState().setPinned('dict:tin');

    useRhymeStore.getState().dismiss('dict:tin');
    const s = useRhymeStore.getState();
    expect(s.candidates.map((c) => c.id)).toEqual(['dict:bin']);
    expect(s.dismissed.has('dict:tin')).toBe(true);
    expect(s.hoveredId).toBeNull();
    expect(s.pinnedId).toBeNull();

    rhymeSuggest.mockResolvedValueOnce(response([]) as never);
    await useRhymeStore.getState().runQuery();
    expect(lastRequest().exclude_ids).toEqual(['dict:tin']);
  });
});

describe('promote', () => {
  async function loadOne() {
    useRhymeStore.getState().enter();
    rhymeSuggest.mockResolvedValueOnce(response([cand('dict:tin', 'tin', 0.9)]) as never);
    await useRhymeStore.getState().runQuery();
  }

  it('creates a snippet with raw_text, the card position, and derived_from, then removes the candidate', async () => {
    await loadOne();
    const created = { ...snippet('new1', 'tin', '1'), snippet_type: 'freestyle' as const };
    createSnippet.mockResolvedValueOnce({ data: created } as never);
    const c = useRhymeStore.getState().candidates[0];
    const centre = useRhymeStore.getState().positions.get('dict:tin')!;
    const expectedTopLeft = candidateTopLeft(centre, candidateCardSize(c));
    const pastBefore = useCanvasStore.getState().past.length;

    await useRhymeStore.getState().promote('dict:tin');

    expect(createSnippet).toHaveBeenCalledTimes(1);
    expect(createSnippet).toHaveBeenCalledWith('c1', {
      raw_text: 'tin',
      position_x: expectedTopLeft.x,
      position_y: expectedTopLeft.y,
      snippet_type: 'freestyle',
      derived_from: {
        provenance: 'dictionary',
        source_unit_id: 'dict:tin',
        score: 0.9,
        match_depth: 1,
        k: 2,
        anchor_unit_id: 'note:a:0:0',
        anchor_text: 'hinge',
      },
    });
    const s = useRhymeStore.getState();
    expect(s.candidates).toEqual([]);
    expect(s.dismissed.has('dict:tin')).toBe(true);
    expect(useCanvasStore.getState().snippets.get('new1')).toEqual(created);
    expect(useCanvasStore.getState().past.length).toBe(pastBefore + 1);
    expect(s.error).toBeNull();
  });

  it('uses the drop position when given', async () => {
    await loadOne();
    createSnippet.mockResolvedValueOnce({ data: snippet('new2', 'tin', '1') } as never);
    await useRhymeStore.getState().promote('dict:tin', { x: 12, y: 34 });
    const body = createSnippet.mock.calls[0][1];
    expect(body.position_x).toBe(12);
    expect(body.position_y).toBe(34);
  });

  it('stores a typed promote error, keeps the candidate, and does not throw', async () => {
    await loadOne();
    createSnippet.mockRejectedValueOnce(axiosErr(500));
    await expect(useRhymeStore.getState().promote('dict:tin')).resolves.toBeUndefined();
    const s = useRhymeStore.getState();
    expect(s.error?.kind).toBe('server');
    expect(s.error?.operation).toBe('promote');
    expect(s.candidates.length).toBe(1);
    expect(useCanvasStore.getState().snippets.size).toBe(2);
  });

  it('ignores unknown ids', async () => {
    await loadOne();
    await useRhymeStore.getState().promote('dict:nope');
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
