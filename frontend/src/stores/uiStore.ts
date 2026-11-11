import { create } from 'zustand';
import { browserStorage, loadViewMode, saveViewMode } from '@/lib/listMode';
import type { ViewMode } from '@/lib/listMode';

interface ContextMenu {
  snippetId: string;
  x: number;
  y: number;
}

/** The "⧉ N" badge popover: the canvases holding a snippet, anchored at a screen point. */
export interface CanvasesPopover {
  snippetId: string;
  x: number;
  y: number;
}

/** The right sidebar: at most one of the rhyme sidebar and the word bank is open. */
export type RightPanel = 'none' | 'rhymes' | 'words';

interface UIState {
  // Panels
  rightPanel: RightPanel;
  similarityPanelOpen: boolean;
  exportDialogOpen: boolean;

  /** Canvas page view: the Pixi canvas or the snippet list. Persisted per viewer. */
  viewMode: ViewMode;

  // Editing state
  editingSnippetId: string | null;
  similarityTargetId: string | null;

  // Context menu
  contextMenu: ContextMenu | null;
  canvasesPopover: CanvasesPopover | null;

  // Actions
  /** Open the rhyme sidebar (closing the word bank), or close it. */
  toggleRhymeSidebar: () => void;
  /** Open the word bank (closing the rhyme sidebar), or close it. */
  toggleWordBank: () => void;
  toggleSimilarityPanel: () => void;
  setExportDialogOpen: (open: boolean) => void;
  /** Switch view; closes the editor and context menu, whose placement belongs to the old view. */
  setViewMode: (mode: ViewMode) => void;
  toggleViewMode: () => void;
  setEditingSnippet: (id: string | null) => void;
  /** Close the editor only if it is still editing `id` (a slow save must not close a newer editor). */
  closeEditor: (id: string) => void;
  setSimilarityTarget: (id: string | null) => void;
  setContextMenu: (menu: ContextMenu | null) => void;
  closeContextMenu: () => void;
  /** Open the badge popover (closing the context menu), or close it with null. */
  setCanvasesPopover: (popover: CanvasesPopover | null) => void;
  /** Close the editor, context menu and badge popover when the open canvas changes. */
  leaveCanvas: () => void;
}

export const useUIStore = create<UIState>((set, get) => ({
  rightPanel: 'none',
  similarityPanelOpen: false,
  exportDialogOpen: false,
  viewMode: loadViewMode(browserStorage()),
  editingSnippetId: null,
  similarityTargetId: null,
  contextMenu: null,
  canvasesPopover: null,

  toggleRhymeSidebar: () => set((s) => ({ rightPanel: s.rightPanel === 'rhymes' ? 'none' : 'rhymes' })),
  toggleWordBank: () => set((s) => ({ rightPanel: s.rightPanel === 'words' ? 'none' : 'words' })),
  toggleSimilarityPanel: () => set((s) => ({ similarityPanelOpen: !s.similarityPanelOpen })),
  setExportDialogOpen: (open) => set({ exportDialogOpen: open }),
  setViewMode: (mode) => {
    // A failed write is already logged; the in-memory mode still applies for this session.
    saveViewMode(browserStorage(), mode);
    set({ viewMode: mode, editingSnippetId: null, contextMenu: null, canvasesPopover: null });
  },
  toggleViewMode: () => get().setViewMode(get().viewMode === 'canvas' ? 'list' : 'canvas'),
  setEditingSnippet: (id) => set({ editingSnippetId: id }),
  closeEditor: (id) => {
    if (get().editingSnippetId === id) set({ editingSnippetId: null });
  },
  setSimilarityTarget: (id) => set({ similarityTargetId: id, similarityPanelOpen: true }),
  setContextMenu: (menu) => set({ contextMenu: menu }),
  closeContextMenu: () => set({ contextMenu: null }),
  setCanvasesPopover: (popover) => set(popover === null ? { canvasesPopover: null } : { canvasesPopover: popover, contextMenu: null }),
  leaveCanvas: () => set({ editingSnippetId: null, contextMenu: null, canvasesPopover: null }),
}));
