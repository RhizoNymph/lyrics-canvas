/**
 * List view sort (per device) and custom reorders: plan (lib/listMode/reorder), apply optimistically to the
 * canvas store, send (one layout PUT, or one batch-position call for a re-rank) on a serial queue so the
 * server sees reorders in the order they were made, and on failure roll back what is still ours and show an
 * error notice. See docs/features/web_list_order.md.
 */
import { create } from 'zustand';
import { placementApi } from '@/api/client';
import { browserStorage } from '@/lib/listMode/viewMode';
import { loadListSort, saveListSort } from '@/lib/listMode/sortPref';
import { planReorder } from '@/lib/listMode/reorder';
import type { ReorderPlan, ReorderRefusal, RankChange } from '@/lib/listMode/reorder';
import type { ListSort } from '@/lib/listMode/sort';
import { describePlacementError, toPlacementError } from '@/lib/placements/errors';
import type { PlacementError } from '@/lib/placements/errors';
import { useCanvasStore } from './canvasStore';
import { useUIStore } from './uiStore';
import { usePlacementNoticeStore } from './placementNoticeStore';

export interface ReorderMove {
  /** Rendered row ids, top to bottom. */
  visible: ReadonlyArray<string>;
  movedId: string;
  /** The moved row's index in `visible` after the move. */
  toIndex: number;
}

export type ReorderOutcome =
  | { ok: true; plan: ReorderPlan }
  /** Nothing was changed or sent. `editing`: an editor is open (the row order is frozen). */
  | { ok: false; reason: ReorderRefusal | 'no_canvas' | 'editing' }
  /** Applied, sent, failed and rolled back. */
  | { ok: false; reason: 'failed'; error: PlacementError };

interface ListOrderState {
  sort: ListSort;
  /** Change the sort and remember it on this device. */
  setSort: (sort: ListSort) => void;
  /** Move one row; resolves once the server answered (the local order changes at once). Never throws. */
  reorder: (move: ReorderMove) => Promise<ReorderOutcome>;
}

/** Reorder requests go out one at a time, in order. */
let queue: Promise<unknown> = Promise.resolve();

function send(canvasId: string, plan: ReorderPlan): Promise<void> {
  if (plan.kind === 'single') {
    const { id, to } = plan.change;
    return placementApi.setListRank(id, canvasId, to).then((saved) => {
      // Take the server's value (it should be ours) unless a later reorder moved the row again.
      if (useCanvasStore.getState().canvasId === canvasId && saved.list_rank !== to) {
        useCanvasStore.getState().applyListRanks([{ id, list_rank: saved.list_rank, expect: to }]);
      }
    });
  }
  return placementApi.setListRanks(
    canvasId,
    plan.changes.map((c) => ({ id: c.id, list_rank: c.to })),
  );
}

function changesOf(plan: ReorderPlan): RankChange[] {
  return plan.kind === 'single' ? [plan.change] : plan.changes;
}

export const useListOrderStore = create<ListOrderState>((set, get) => ({
  sort: loadListSort(browserStorage()),

  setSort: (sort) => {
    saveListSort(browserStorage(), sort);
    set({ sort });
  },

  reorder: async (move) => {
    const canvas = useCanvasStore.getState();
    const canvasId = canvas.canvasId;
    if (canvasId === null) return { ok: false, reason: 'no_canvas' };
    if (useUIStore.getState().editingSnippetId !== null) return { ok: false, reason: 'editing' };

    const sortBefore = get().sort;
    const planned = planReorder({ sort: sortBefore, all: [...canvas.snippets.values()], ...move });
    if (!planned.ok) return planned;
    const plan = planned.plan;
    const changes = changesOf(plan);

    canvas.applyListRanks(changes.map((c) => ({ id: c.id, list_rank: c.to })));
    if (plan.kind === 'rerank' && sortBefore !== 'custom') get().setSort('custom');
    console.info('list order: reorder', {
      canvas_id: canvasId,
      snippet_id: move.movedId,
      kind: plan.kind,
      count: changes.length,
      adopted: plan.kind === 'rerank' ? plan.adopted : 'custom',
    });

    const request = queue.then(() => send(canvasId, plan));
    queue = request.catch(() => undefined);
    try {
      await request;
      return { ok: true, plan };
    } catch (err) {
      const error = toPlacementError(err, 'save_list_order');
      console.warn('list order: save failed, rolling back', {
        canvas_id: canvasId,
        snippet_id: move.movedId,
        kind: error.kind,
        status: error.status,
      });
      if (useCanvasStore.getState().canvasId === canvasId) {
        useCanvasStore.getState().applyListRanks(changes.map((c) => ({ id: c.id, list_rank: c.from, expect: c.to })));
        // A re-rank switched the sort to Custom; go back unless the user picked another sort since.
        if (plan.kind === 'rerank' && sortBefore !== 'custom' && get().sort === 'custom') get().setSort(sortBefore);
      }
      usePlacementNoticeStore.getState().show(`Could not save the list order. ${describePlacementError(error)}`, 'error');
      return { ok: false, reason: 'failed', error };
    }
  },
}));
