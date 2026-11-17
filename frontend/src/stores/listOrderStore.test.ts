import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError } from 'axios';
import { placementApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { useUIStore } from './uiStore';
import { usePlacementNoticeStore } from './placementNoticeStore';
import { useListOrderStore } from './listOrderStore';
import { snippet } from '@/lib/merge/fixtures';
import { PlacementError } from '@/lib/placements';
import { nKeysBetween } from '@/lib/listMode/rank';
import { sortSnippets } from '@/lib/listMode/sort';
import type { Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
  placementApi: {
    setListRank: vi.fn(),
    setListRanks: vi.fn(),
  },
}));

const api = vi.mocked(placementApi);

/** a..d in custom order; A-Z order is the reverse (texts z, y, x, w). */
function seed(): Snippet[] {
  const keys = nKeysBetween(null, null, 4);
  return [
    snippet('a', 'zulu', { list_rank: keys[0], placement_id: 'pa' }),
    snippet('b', 'yankee', { list_rank: keys[1], placement_id: 'pb' }),
    snippet('c', 'xray', { list_rank: keys[2], placement_id: 'pc' }),
    snippet('d', 'whiskey', { list_rank: keys[3], placement_id: 'pd' }),
  ];
}

function customOrder(): string[] {
  return sortSnippets('custom', [...useCanvasStore.getState().snippets.values()]).map((s) => s.id);
}

function ranks(): Record<string, string> {
  return Object.fromEntries([...useCanvasStore.getState().snippets.values()].map((s) => [s.id, s.list_rank]));
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map(seed().map((s) => [s.id, s])),
    selectedSnippetIds: new Set(['b', 'c']),
    past: [],
    future: [],
  });
  useUIStore.setState({ editingSnippetId: null });
  usePlacementNoticeStore.setState({ notice: null });
  useListOrderStore.setState({ sort: 'custom' });
});

describe('reorder in Custom', () => {
  it('applies the new rank at once and sends one layout PUT with list_rank only', async () => {
    const pending = deferred<Snippet>();
    api.setListRank.mockReturnValueOnce(pending.promise);
    const done = useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 2 });

    // Optimistic: before the server answers.
    expect(customOrder()).toEqual(['b', 'c', 'a', 'd']);
    const rank = ranks().a;
    await Promise.resolve();
    expect(api.setListRank).toHaveBeenCalledWith('a', 'c1', rank);
    expect(api.setListRanks).not.toHaveBeenCalled();

    pending.resolve({ ...useCanvasStore.getState().snippets.get('a')! });
    const outcome = await done;
    expect(outcome.ok).toBe(true);
    expect(customOrder()).toEqual(['b', 'c', 'a', 'd']);
  });

  it('leaves the selection and the history alone', async () => {
    api.setListRank.mockImplementation(async (id) => useCanvasStore.getState().snippets.get(id)!);
    await useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'd', toIndex: 0 });
    expect(useCanvasStore.getState().selectedSnippetIds).toEqual(new Set(['b', 'c']));
    expect(useCanvasStore.getState().past).toEqual([]);
  });

  it('rolls back and shows an error notice when the PUT fails', async () => {
    const before = ranks();
    api.setListRank.mockRejectedValueOnce(new PlacementError('network', 'save_list_order', 'down'));
    const outcome = await useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 3 });
    expect(outcome).toMatchObject({ ok: false, reason: 'failed' });
    expect(ranks()).toEqual(before);
    expect(customOrder()).toEqual(['a', 'b', 'c', 'd']);
    const notice = usePlacementNoticeStore.getState().notice;
    expect(notice?.tone).toBe('error');
    expect(notice?.text).toMatch(/list order/i);
  });

  it('maps a raw axios failure to a typed error', async () => {
    api.setListRank.mockRejectedValueOnce(new AxiosError('boom'));
    const outcome = await useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 1 });
    expect(outcome.ok).toBe(false);
    if (!outcome.ok && outcome.reason === 'failed') {
      expect(outcome.error).toBeInstanceOf(PlacementError);
      expect(outcome.error.operation).toBe('save_list_order');
    }
  });

  it('a failed reorder does not undo a later reorder of the same row', async () => {
    const first = deferred<Snippet>();
    api.setListRank.mockReturnValueOnce(first.promise);
    api.setListRank.mockImplementationOnce(async (id) => useCanvasStore.getState().snippets.get(id)!);
    const p1 = useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 1 });
    const p2 = useListOrderStore.getState().reorder({ visible: ['b', 'a', 'c', 'd'], movedId: 'a', toIndex: 3 });
    expect(customOrder()).toEqual(['b', 'c', 'd', 'a']);
    first.reject(new PlacementError('server', 'save_list_order', 'x', 500));
    await p1;
    await p2;
    // The first rollback only applies where its own rank is still in place, so the second move stands.
    expect(customOrder()).toEqual(['b', 'c', 'd', 'a']);
  });

  it('sends reorders one at a time, in the order they were made', async () => {
    const first = deferred<Snippet>();
    api.setListRank.mockReturnValueOnce(first.promise);
    api.setListRank.mockImplementationOnce(async (id) => useCanvasStore.getState().snippets.get(id)!);
    const p1 = useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 1 });
    const p2 = useListOrderStore.getState().reorder({ visible: ['b', 'a', 'c', 'd'], movedId: 'c', toIndex: 0 });
    await Promise.resolve();
    await Promise.resolve();
    expect(api.setListRank).toHaveBeenCalledTimes(1);
    first.resolve(useCanvasStore.getState().snippets.get('a')!);
    await p1;
    await p2;
    expect(api.setListRank).toHaveBeenCalledTimes(2);
    expect(api.setListRank.mock.calls.map((c) => c[0])).toEqual(['a', 'c']);
  });

  it('takes the rank the server echoes', async () => {
    api.setListRank.mockImplementationOnce(async (id) => ({ ...useCanvasStore.getState().snippets.get(id)!, list_rank: 'Zz' }));
    await useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'c', toIndex: 0 });
    expect(ranks().c).toBe('Zz');
  });

  it('only visible neighbours count (filtered rows are skipped)', async () => {
    api.setListRank.mockImplementation(async (id) => useCanvasStore.getState().snippets.get(id)!);
    // b and c hidden: move a below d.
    await useListOrderStore.getState().reorder({ visible: ['a', 'd'], movedId: 'a', toIndex: 1 });
    expect(customOrder()).toEqual(['b', 'c', 'd', 'a']);
    expect(api.setListRanks).not.toHaveBeenCalled();
  });
});

describe('reorder in another sort', () => {
  beforeEach(() => useListOrderStore.setState({ sort: 'a_z' }));

  it('re-ranks every placement in the current sort with one batch call and switches to Custom', async () => {
    api.setListRanks.mockResolvedValueOnce(undefined);
    // A-Z: d (whiskey), c (xray), b (yankee), a (zulu). Filter hides b. Move a to the top.
    const outcome = await useListOrderStore.getState().reorder({ visible: ['d', 'c', 'a'], movedId: 'a', toIndex: 0 });
    expect(outcome.ok).toBe(true);
    expect(useListOrderStore.getState().sort).toBe('custom');
    expect(customOrder()).toEqual(['a', 'd', 'c', 'b']);
    expect(api.setListRank).not.toHaveBeenCalled();
    expect(api.setListRanks).toHaveBeenCalledTimes(1);
    const [canvasId, items] = api.setListRanks.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect(items.map((i) => i.id)).toEqual(['a', 'd', 'c', 'b']);
    expect(items.map((i) => i.list_rank)).toEqual(nKeysBetween(null, null, 4));
  });

  it('rolls back the ranks and the sort when the batch fails', async () => {
    const before = ranks();
    api.setListRanks.mockRejectedValueOnce(new PlacementError('invalid_list_rank', 'save_list_order', 'bad', 422));
    const outcome = await useListOrderStore.getState().reorder({ visible: ['d', 'c', 'b', 'a'], movedId: 'a', toIndex: 0 });
    expect(outcome).toMatchObject({ ok: false, reason: 'failed' });
    expect(ranks()).toEqual(before);
    expect(useListOrderStore.getState().sort).toBe('a_z');
    expect(usePlacementNoticeStore.getState().notice?.tone).toBe('error');
  });

  it('keeps a sort the user picked while the batch was in flight', async () => {
    const pending = deferred<void>();
    api.setListRanks.mockReturnValueOnce(pending.promise);
    const done = useListOrderStore.getState().reorder({ visible: ['d', 'c', 'b', 'a'], movedId: 'a', toIndex: 0 });
    useListOrderStore.getState().setSort('created');
    pending.reject(new PlacementError('server', 'save_list_order', 'x', 500));
    await done;
    expect(useListOrderStore.getState().sort).toBe('created');
  });
});

describe('refusals', () => {
  it('does nothing while an editor is open (the order is frozen)', async () => {
    useUIStore.setState({ editingSnippetId: 'b' });
    const before = ranks();
    const outcome = await useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 2 });
    expect(outcome).toEqual({ ok: false, reason: 'editing' });
    expect(ranks()).toEqual(before);
    expect(api.setListRank).not.toHaveBeenCalled();
  });

  it('does nothing without a canvas, for a same-place drop, or an unknown row', async () => {
    const store = useListOrderStore.getState();
    expect(await store.reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'b', toIndex: 1 })).toEqual({ ok: false, reason: 'unchanged' });
    expect(await store.reorder({ visible: ['a', 'b'], movedId: 'c', toIndex: 0 })).toEqual({ ok: false, reason: 'not_visible' });
    useCanvasStore.setState({ canvasId: null });
    expect(await store.reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 2 })).toEqual({ ok: false, reason: 'no_canvas' });
    expect(api.setListRank).not.toHaveBeenCalled();
    expect(api.setListRanks).not.toHaveBeenCalled();
  });
});

describe('a canvas switch mid-flight', () => {
  it('does not roll back into another canvas', async () => {
    const pending = deferred<Snippet>();
    api.setListRank.mockReturnValueOnce(pending.promise);
    const done = useListOrderStore.getState().reorder({ visible: ['a', 'b', 'c', 'd'], movedId: 'a', toIndex: 2 });
    const other = snippet('a', 'other canvas copy', { canvas_id: 'c2', list_rank: 'a5' });
    useCanvasStore.setState({ canvasId: 'c2', snippets: new Map([['a', other]]) });
    pending.reject(new PlacementError('network', 'save_list_order', 'x'));
    await done;
    expect(useCanvasStore.getState().snippets.get('a')!.list_rank).toBe('a5');
  });
});

describe('setSort', () => {
  it('changes the sort', () => {
    useListOrderStore.getState().setSort('created');
    expect(useListOrderStore.getState().sort).toBe('created');
  });
});
