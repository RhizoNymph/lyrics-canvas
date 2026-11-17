import { create } from 'zustand';
import { browserStorage } from '@/lib/listMode/viewMode';
import { loadRhymeOrder, saveRhymeOrder } from '@/lib/rhyme/order';
import type { RhymeOrder } from '@/lib/rhyme/order';

/**
 * The viewer's rhyme order ("Rhyme" | "Rhyme, then meaning"), shared by the rhyme sidebar, the pattern
 * columns and the word bank's "rhymes with" view, and remembered in browser storage. The views watch it
 * and refetch from the first page when it changes.
 */
export interface RhymeOrderState {
  order: RhymeOrder;
  setOrder: (order: RhymeOrder) => void;
}

export const useRhymeOrderStore = create<RhymeOrderState>((set, get) => ({
  order: loadRhymeOrder(browserStorage()),
  setOrder: (order) => {
    if (order === get().order) return;
    set({ order });
    // A failed write keeps the in-memory order; it is logged by saveRhymeOrder.
    saveRhymeOrder(browserStorage(), order);
  },
}));

/** The current order, for stores building requests outside React. */
export function currentRhymeOrder(): RhymeOrder {
  return useRhymeOrderStore.getState().order;
}
