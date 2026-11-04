import { create } from 'zustand';
import { browserFilterStorage, EMPTY_FILTER, filterReducer, loadFilter, saveFilter, toPersisted } from '@/lib/tagFilter';
import type { FilterAction, FilterState } from '@/lib/tagFilter';

/**
 * The canvas page's tag expression filter (canvas and list view). One canvas at a time; its text is
 * persisted per canvas in this browser. Visibility reads `filter.applied`, which only changes to
 * expressions that parse.
 */
interface TagFilterStoreState {
  canvasId: string | null;
  filter: FilterState;
  /** Switch to a canvas, restoring the filter this viewer last used on it. */
  open: (canvasId: string) => void;
  dispatch: (action: FilterAction) => void;
}

export const useTagFilterStore = create<TagFilterStoreState>((set, get) => ({
  canvasId: null,
  filter: EMPTY_FILTER,

  open: (canvasId) => {
    if (get().canvasId === canvasId) return;
    const persisted = loadFilter(browserFilterStorage(), canvasId);
    set({ canvasId, filter: filterReducer(EMPTY_FILTER, { type: 'restore', persisted }) });
  },

  dispatch: (action) => {
    const { canvasId, filter } = get();
    const next = filterReducer(filter, action);
    if (next === filter) return;
    set({ filter: next });
    // A failed write is already logged; the in-memory filter still applies for this session.
    if (canvasId !== null) saveFilter(browserFilterStorage(), canvasId, toPersisted(next));
  },
}));
