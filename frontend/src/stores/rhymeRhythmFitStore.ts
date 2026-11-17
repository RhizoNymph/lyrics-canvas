import { create } from 'zustand';
import { browserStorage } from '@/lib/listMode/viewMode';
import { loadRhythmFit, saveRhythmFit } from '@/lib/rhyme/rhythmFit';

/**
 * The viewer's "Fit rhythm" switch for the rhyme sidebar, remembered in browser storage. Only sent when
 * the query anchors on a snippet (lib/rhyme/rhythmFit.ts); the sidebar re-queries from the first page
 * when it changes.
 */
export interface RhymeRhythmFitState {
  on: boolean;
  setOn: (on: boolean) => void;
}

export const useRhymeRhythmFitStore = create<RhymeRhythmFitState>((set, get) => ({
  on: loadRhythmFit(browserStorage()),
  setOn: (on) => {
    if (on === get().on) return;
    set({ on });
    // A failed write keeps the in-memory switch; it is logged by saveRhythmFit.
    saveRhythmFit(browserStorage(), on);
  },
}));

/** The current switch, for stores building requests outside React. */
export function currentRhythmFit(): boolean {
  return useRhymeRhythmFitStore.getState().on;
}
