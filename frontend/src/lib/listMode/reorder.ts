/**
 * Planning a list reorder (drag or Alt+Up/Down) as rank changes, without touching state:
 * - in Custom, a drop between visible neighbours P and N is one change: `keyBetween(P.rank, N.rank)`;
 * - in another sort (or when P and N tie / cannot be bisected), every placement on the canvas, filtered-out
 *   ones included, is re-ranked in the current sort with the moved row inserted at the drop point
 *   (`nKeysBetween(null, null, n)`), and the caller switches the sort to Custom.
 */
import { keyBetween, nKeysBetween, RankError } from './rank';
import { sortSnippets } from './sort';
import type { ListSort, Sortable } from './sort';

export interface ReorderRequest {
  sort: ListSort;
  /** Rendered row ids, top to bottom (only rows the filters show). */
  visible: ReadonlyArray<string>;
  /** Every snippet on the canvas, filtered-out ones included. */
  all: ReadonlyArray<Sortable>;
  movedId: string;
  /** The moved row's index in `visible` after the move (clamped into the list). */
  toIndex: number;
}

export interface RankChange {
  id: string;
  from: string;
  to: string;
}

export type ReorderPlan =
  /** Custom order: one layout PUT. */
  | { kind: 'single'; change: RankChange }
  /** Every placement on the canvas, top to bottom: one batch-position call; the sort becomes Custom. */
  | { kind: 'rerank'; adopted: ListSort; changes: RankChange[] };

export type ReorderRefusal =
  /** The drop leaves the row where it is. */
  | 'unchanged'
  /** The moved row is not rendered. */
  | 'not_visible'
  /** A rendered id is not a snippet on the canvas. */
  | 'not_on_canvas';

export type ReorderPlanResult = { ok: true; plan: ReorderPlan } | { ok: false; reason: ReorderRefusal };

export function planReorder(req: ReorderRequest): ReorderPlanResult {
  const from = req.visible.indexOf(req.movedId);
  if (from < 0) return { ok: false, reason: 'not_visible' };
  const byId = new Map(req.all.map((s) => [s.id, s]));
  if (req.visible.some((id) => !byId.has(id))) return { ok: false, reason: 'not_on_canvas' };

  const rest = req.visible.filter((id) => id !== req.movedId);
  const to = Math.max(0, Math.min(rest.length, Math.trunc(req.toIndex)));
  if (to === from) return { ok: false, reason: 'unchanged' };
  const above = to > 0 ? rest[to - 1] : null;
  const below = to < rest.length ? rest[to] : null;
  const moved = byId.get(req.movedId)!;

  if (req.sort === 'custom') {
    const lo = above === null ? null : byId.get(above)!.list_rank;
    const hi = below === null ? null : byId.get(below)!.list_rank;
    try {
      const rank = keyBetween(lo, hi);
      return { ok: true, plan: { kind: 'single', change: { id: moved.id, from: moved.list_rank, to: rank } } };
    } catch (err) {
      if (!(err instanceof RankError)) throw err;
      console.warn('list order: neighbours cannot be bisected, re-ranking', {
        moved: moved.id,
        above: lo,
        below: hi,
        kind: err.kind,
      });
    }
  }
  return { ok: true, plan: rerank(req.sort, req.all, moved, above, below) };
}

function rerank(
  sort: ListSort,
  all: ReadonlyArray<Sortable>,
  moved: Sortable,
  above: string | null,
  below: string | null,
): ReorderPlan {
  const base = sortSnippets(sort, all).filter((s) => s.id !== moved.id);
  const at =
    above !== null
      ? base.findIndex((s) => s.id === above) + 1
      : below !== null
        ? base.findIndex((s) => s.id === below)
        : 0;
  base.splice(at, 0, moved);
  const keys = nKeysBetween(null, null, base.length);
  return {
    kind: 'rerank',
    adopted: sort,
    changes: base.map((s, i) => ({ id: s.id, from: s.list_rank, to: keys[i] })),
  };
}

/** The target index for Alt+Up (-1) / Alt+Down (+1), or null at an edge or for an unknown row. */
export function stepTarget(visible: ReadonlyArray<string>, id: string, delta: -1 | 1): number | null {
  const i = visible.indexOf(id);
  if (i < 0) return null;
  const to = i + delta;
  return to < 0 || to >= visible.length ? null : to;
}

/** The gap the pointer is over: the number of row midpoints (top to bottom) above `y`. 0..mids.length. */
export function dropSlot(mids: ReadonlyArray<number>, y: number): number {
  let slot = 0;
  for (const mid of mids) if (mid < y) slot++;
  return slot;
}

/** The dragged row's new index when dropped into `slot`. */
export function slotToIndex(slot: number, fromIndex: number): number {
  return slot > fromIndex ? slot - 1 : slot;
}

/** The gaps directly above and below the dragged row leave it in place. */
export function isNoopSlot(slot: number, fromIndex: number): boolean {
  return slot === fromIndex || slot === fromIndex + 1;
}
