import { describe, it, expect } from 'vitest';
import { dropSlot, isNoopSlot, planReorder, slotToIndex, stepTarget } from './reorder';
import type { ReorderPlan, ReorderRequest } from './reorder';
import { compareRanks, nKeysBetween } from './rank';
import { sortSnippets } from './sort';
import { snippet } from '@/lib/merge/fixtures';
import type { Snippet } from '@/types';

/** Snippets ranked in the given order: a0, a1, ... */
function ranked(...ids: string[]): Snippet[] {
  const keys = nKeysBetween(null, null, ids.length);
  return ids.map((id, i) => snippet(id, id, { list_rank: keys[i], placement_id: `p-${id}` }));
}

function plan(req: Partial<ReorderRequest> & Pick<ReorderRequest, 'all' | 'movedId' | 'toIndex'>): ReorderPlan {
  const result = planReorder({
    sort: 'custom',
    visible: sortSnippets(req.sort ?? 'custom', req.all).map((s) => s.id),
    ...req,
  });
  if (!result.ok) throw new Error(`refused: ${result.reason}`);
  return result.plan;
}

/** The canvas's custom order once `p` is applied. */
function orderAfter(all: Snippet[], p: ReorderPlan): string[] {
  const ranks = new Map(all.map((s) => [s.id, s.list_rank]));
  const changes = p.kind === 'single' ? [p.change] : p.changes;
  for (const c of changes) ranks.set(c.id, c.to);
  return sortSnippets(
    'custom',
    all.map((s) => ({ ...s, list_rank: ranks.get(s.id)! })),
  ).map((s) => s.id);
}

describe('planReorder in Custom', () => {
  const all = ranked('a', 'b', 'c', 'd');

  it('moving down between two neighbours takes a key between their ranks (one change)', () => {
    const p = plan({ all, movedId: 'a', toIndex: 2 });
    expect(p.kind).toBe('single');
    if (p.kind !== 'single') return;
    expect(p.change.id).toBe('a');
    expect(p.change.from).toBe(all[0].list_rank);
    expect(compareRanks(all[2].list_rank, p.change.to)).toBeLessThan(0);
    expect(compareRanks(p.change.to, all[3].list_rank)).toBeLessThan(0);
    expect(orderAfter(all, p)).toEqual(['b', 'c', 'a', 'd']);
  });

  it('moving to the top uses key_between(null, first)', () => {
    const p = plan({ all, movedId: 'c', toIndex: 0 });
    expect(p.kind).toBe('single');
    expect(orderAfter(all, p)).toEqual(['c', 'a', 'b', 'd']);
    if (p.kind === 'single') expect(compareRanks(p.change.to, all[0].list_rank)).toBeLessThan(0);
  });

  it('moving to the bottom uses key_between(last, null)', () => {
    const p = plan({ all, movedId: 'a', toIndex: 3 });
    expect(orderAfter(all, p)).toEqual(['b', 'c', 'd', 'a']);
  });

  it('clamps the target index into the list', () => {
    expect(orderAfter(all, plan({ all, movedId: 'b', toIndex: 99 }))).toEqual(['a', 'c', 'd', 'b']);
    expect(orderAfter(all, plan({ all, movedId: 'b', toIndex: -5 }))).toEqual(['b', 'a', 'c', 'd']);
  });

  it('refuses a drop on the same place', () => {
    const visible = ['a', 'b', 'c', 'd'];
    expect(planReorder({ sort: 'custom', visible, all, movedId: 'b', toIndex: 1 })).toEqual({ ok: false, reason: 'unchanged' });
  });

  it('refuses a row that is not visible or not on the canvas', () => {
    expect(planReorder({ sort: 'custom', visible: ['a', 'b'], all, movedId: 'c', toIndex: 0 })).toEqual({
      ok: false,
      reason: 'not_visible',
    });
    expect(planReorder({ sort: 'custom', visible: ['a', 'zz'], all, movedId: 'zz', toIndex: 0 })).toEqual({
      ok: false,
      reason: 'not_on_canvas',
    });
  });

  it('only considers visible neighbours: hidden rows do not count, the key stays valid globally', () => {
    // b and c are filtered out; drop a between d and e.
    const five = ranked('a', 'b', 'c', 'd', 'e');
    const visible = ['a', 'd', 'e'];
    const p = plan({ all: five, visible, movedId: 'a', toIndex: 1 });
    expect(p.kind).toBe('single');
    if (p.kind !== 'single') return;
    expect(compareRanks(five[3].list_rank, p.change.to)).toBeLessThan(0);
    expect(compareRanks(p.change.to, five[4].list_rank)).toBeLessThan(0);
    expect(orderAfter(five, p)).toEqual(['b', 'c', 'd', 'a', 'e']);
  });

  it('a single visible row has nowhere to go', () => {
    expect(planReorder({ sort: 'custom', visible: ['b'], all, movedId: 'b', toIndex: 0 })).toEqual({
      ok: false,
      reason: 'unchanged',
    });
  });

  it('falls back to re-ranking the custom order when neighbours tie (two offline devices chose one key)', () => {
    const tied = [
      snippet('x', 'x', { list_rank: 'a0', placement_id: 'p1' }),
      snippet('y', 'y', { list_rank: 'a0', placement_id: 'p2' }),
      snippet('z', 'z', { list_rank: 'a1', placement_id: 'p3' }),
    ];
    // Custom order: x, y, z. Move z between x and y.
    const p = plan({ all: tied, movedId: 'z', toIndex: 1 });
    expect(p.kind).toBe('rerank');
    if (p.kind !== 'rerank') return;
    expect(p.adopted).toBe('custom');
    expect(p.changes.map((c) => c.id)).toEqual(['x', 'z', 'y']);
    expect(p.changes.map((c) => c.to)).toEqual(nKeysBetween(null, null, 3));
    expect(p.changes.map((c) => c.from)).toEqual(['a0', 'a1', 'a0']);
    expect(orderAfter(tied, p)).toEqual(['x', 'z', 'y']);
  });
});

describe('planReorder in another sort', () => {
  // A-Z order: apple, banana, cherry, date; custom order is the reverse.
  const all = [
    snippet('d', 'date', { list_rank: 'a0', placement_id: 'p-d' }),
    snippet('c', 'cherry', { list_rank: 'a1', placement_id: 'p-c' }),
    snippet('b', 'banana', { list_rank: 'a2', placement_id: 'p-b' }),
    snippet('a', 'apple', { list_rank: 'a3', placement_id: 'p-a' }),
  ];

  it('adopts the current sort for every placement, inserts the moved row, ranks with nKeysBetween(null, null, n)', () => {
    const p = plan({ sort: 'a_z', all, movedId: 'a', toIndex: 2 });
    expect(p.kind).toBe('rerank');
    if (p.kind !== 'rerank') return;
    expect(p.adopted).toBe('a_z');
    expect(p.changes.map((c) => c.id)).toEqual(['b', 'c', 'a', 'd']);
    expect(p.changes.map((c) => c.to)).toEqual(nKeysBetween(null, null, 4));
    expect(p.changes.find((c) => c.id === 'a')!.from).toBe('a3');
    expect(orderAfter(all, p)).toEqual(['b', 'c', 'a', 'd']);
  });

  it('includes filtered-out placements, sorted by the same sort, and inserts after the visible row above', () => {
    // Visible (A-Z): apple, cherry (banana and date are filtered out). Move apple below cherry.
    const p = plan({ sort: 'a_z', all, visible: ['a', 'c'], movedId: 'a', toIndex: 1 });
    expect(p.kind).toBe('rerank');
    if (p.kind !== 'rerank') return;
    expect(p.changes.map((c) => c.id)).toEqual(['b', 'c', 'a', 'd']);
  });

  it('a drop at the top goes right before the visible row below', () => {
    // Visible: banana, date. Move date to the top: before banana, after (hidden) apple.
    const p = plan({ sort: 'a_z', all, visible: ['b', 'd'], movedId: 'd', toIndex: 0 });
    if (p.kind !== 'rerank') throw new Error('expected rerank');
    expect(p.changes.map((c) => c.id)).toEqual(['a', 'd', 'b', 'c']);
  });

  it('re-ranks even when the drop matches the custom order already (one batch, then Custom)', () => {
    const p = plan({ sort: 'last_edited', all: ranked('a', 'b', 'c'), movedId: 'c', toIndex: 0 });
    expect(p.kind).toBe('rerank');
  });

  it('refuses a drop on the same place in another sort too', () => {
    const visible = sortSnippets('a_z', all).map((s) => s.id);
    expect(planReorder({ sort: 'a_z', visible, all, movedId: 'c', toIndex: 2 })).toEqual({ ok: false, reason: 'unchanged' });
  });
});

describe('stepTarget (Alt+Up / Alt+Down)', () => {
  const visible = ['a', 'b', 'c'];
  it('moves one visible row up or down', () => {
    expect(stepTarget(visible, 'b', -1)).toBe(0);
    expect(stepTarget(visible, 'b', 1)).toBe(2);
  });
  it('is null at the edges and for unknown rows', () => {
    expect(stepTarget(visible, 'a', -1)).toBeNull();
    expect(stepTarget(visible, 'c', 1)).toBeNull();
    expect(stepTarget(visible, 'x', 1)).toBeNull();
  });
});

describe('pointer drop slots', () => {
  const mids = [10, 30, 50, 70];
  it('counts the row midpoints above the pointer', () => {
    expect(dropSlot(mids, 0)).toBe(0);
    expect(dropSlot(mids, 20)).toBe(1);
    expect(dropSlot(mids, 55)).toBe(3);
    expect(dropSlot(mids, 1000)).toBe(4);
  });
  it('maps a slot to the moved row index', () => {
    expect(slotToIndex(0, 2)).toBe(0);
    expect(slotToIndex(2, 2)).toBe(2);
    expect(slotToIndex(3, 2)).toBe(2);
    expect(slotToIndex(4, 2)).toBe(3);
  });
  it('the gaps either side of the dragged row are no-ops', () => {
    expect(isNoopSlot(2, 2)).toBe(true);
    expect(isNoopSlot(3, 2)).toBe(true);
    expect(isNoopSlot(1, 2)).toBe(false);
    expect(isNoopSlot(4, 2)).toBe(false);
  });
});
