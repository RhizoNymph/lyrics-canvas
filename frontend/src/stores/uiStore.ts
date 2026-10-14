import { create } from 'zustand';
import { browserStorage, loadViewMode, saveViewMode } from '@/lib/listMode';
import type { ViewMode } from '@/lib/listMode';

interface ContextMenu {
  snippetId: string;
  x: number;
  y: number;
}

interface UIState {
  // Panels
  rhymeSidebarOpen: boolean;
  similarityPanelOpen: boolean;
  searchModalOpen: boolean;
  exportDialogOpen: boolean;

  /** Canvas page view: the Pixi canvas or the snippet list. Persisted per viewer. */
  viewMode: ViewMode;

  // Editing state
  editingSnippetId: string | null;
  similarityTargetId: string | null;

  // Context menu
  contextMenu: ContextMenu | null;

  // Actions
  toggleRhymeSidebar: () => void;
  toggleSimilarityPanel: () => void;
  setSearchModalOpen: (open: boolean) => void;
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
}

export const useUIStore = create<UIState>((set, get) => ({
  rhymeSidebarOpen: false,
  similarityPanelOpen: false,
  searchModalOpen: false,
  exportDialogOpen: false,
  viewMode: loadViewMode(browserStorage()),
  editingSnippetId: null,
  similarityTargetId: null,
  contextMenu: null,

  toggleRhymeSidebar: () => set((s) => ({ rhymeSidebarOpen: !s.rhymeSidebarOpen })),
  toggleSimilarityPanel: () => set((s) => ({ similarityPanelOpen: !s.similarityPanelOpen })),
  setSearchModalOpen: (open) => set({ searchModalOpen: open }),
  setExportDialogOpen: (open) => set({ exportDialogOpen: open }),
  setViewMode: (mode) => {
    // A failed write is already logged; the in-memory mode still applies for this session.
    saveViewMode(browserStorage(), mode);
    set({ viewMode: mode, editingSnippetId: null, contextMenu: null });
  },
  toggleViewMode: () => get().setViewMode(get().viewMode === 'canvas' ? 'list' : 'canvas'),
  setEditingSnippet: (id) => set({ editingSnippetId: id }),
  closeEditor: (id) => {
    if (get().editingSnippetId === id) set({ editingSnippetId: null });
  },
  setSimilarityTarget: (id) => set({ similarityTargetId: id, similarityPanelOpen: true }),
  setContextMenu: (menu) => set({ contextMenu: menu }),
  closeContextMenu: () => set({ contextMenu: null }),
}));
