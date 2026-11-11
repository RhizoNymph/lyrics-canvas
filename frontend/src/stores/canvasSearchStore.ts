import { create } from 'zustand';
import { EMPTY_SEARCH, parseSearchQuery } from '@/lib/search/match';
import type { SearchQuery } from '@/lib/search/match';

/** About 150 ms after the last keystroke the in-canvas search applies (contract §3a). */
export const CANVAS_SEARCH_DEBOUNCE_MS = 150;

/**
 * The in-canvas search box (canvas and list view): `text` is the box, `query` the applied filter after the
 * debounce. One canvas at a time; the text of each canvas lasts for the session only (memory, never
 * persisted). Visibility reads `query` (lib/canvas/visibility.ts).
 */
interface CanvasSearchState {
  canvasId: string | null;
  text: string;
  query: SearchQuery;
  /** Bumped by Ctrl/Cmd+F; the box focuses itself when it changes. */
  focusRequest: number;
  /** A snippet list mode should scroll to (opened from a search hit); null when none. */
  revealSnippetId: string | null;
  /** Switch to a canvas, restoring the text it had earlier this session (applied at once). */
  open: (canvasId: string) => void;
  setText: (text: string) => void;
  /** Empty the box and the filter immediately. */
  clear: () => void;
  requestFocus: () => void;
  requestReveal: (snippetId: string) => void;
  /** Drop the reveal request only if it is still for `snippetId`. */
  clearReveal: (snippetId: string) => void;
}

/** Text per canvas for this session. */
const sessionTexts = new Map<string, string>();
let timer: ReturnType<typeof setTimeout> | null = null;

function cancelPending(): void {
  if (timer !== null) clearTimeout(timer);
  timer = null;
}

export const useCanvasSearchStore = create<CanvasSearchState>((set, get) => ({
  canvasId: null,
  text: '',
  query: EMPTY_SEARCH,
  focusRequest: 0,
  revealSnippetId: null,

  open: (canvasId) => {
    if (get().canvasId === canvasId) return;
    cancelPending();
    const text = sessionTexts.get(canvasId) ?? '';
    set({ canvasId, text, query: parseSearchQuery(text), revealSnippetId: null });
  },

  setText: (text) => {
    const { canvasId } = get();
    if (canvasId !== null) sessionTexts.set(canvasId, text);
    set({ text });
    cancelPending();
    timer = setTimeout(() => {
      timer = null;
      if (get().canvasId === canvasId) set({ query: parseSearchQuery(text) });
    }, CANVAS_SEARCH_DEBOUNCE_MS);
  },

  clear: () => {
    cancelPending();
    const { canvasId } = get();
    if (canvasId !== null) sessionTexts.delete(canvasId);
    set({ text: '', query: EMPTY_SEARCH });
  },

  requestFocus: () => set((s) => ({ focusRequest: s.focusRequest + 1 })),
  requestReveal: (snippetId) => set({ revealSnippetId: snippetId }),
  clearReveal: (snippetId) => {
    if (get().revealSnippetId === snippetId) set({ revealSnippetId: null });
  },
}));

/** Tests: forget every canvas's text and reset the store. */
export function resetCanvasSearchSession(): void {
  cancelPending();
  sessionTexts.clear();
  useCanvasSearchStore.setState({ canvasId: null, text: '', query: EMPTY_SEARCH, revealSnippetId: null });
}
