import { create } from 'zustand';

/**
 * Which snippet the canvas-level "Make score" dialog (ScoreDraftHost) is open for. Opened from the
 * snippet context menu; the inline editor mounts its own ScoreDraftDialog instead.
 */
export interface ScoreDraftState {
  target: { snippetId: string } | null;
  open: (snippetId: string) => void;
  close: () => void;
}

export const useScoreDraftStore = create<ScoreDraftState>((set) => ({
  target: null,
  open: (snippetId) => set({ target: { snippetId } }),
  close: () => set({ target: null }),
}));
