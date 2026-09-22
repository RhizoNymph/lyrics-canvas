import { create } from 'zustand';

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

  // Editing state
  editingSnippetId: string | null;
  rhymeWord: string;
  similarityTargetId: string | null;

  // Context menu
  contextMenu: ContextMenu | null;

  // Actions
  toggleRhymeSidebar: () => void;
  toggleSimilarityPanel: () => void;
  setSearchModalOpen: (open: boolean) => void;
  setExportDialogOpen: (open: boolean) => void;
  setEditingSnippet: (id: string | null) => void;
  setRhymeWord: (word: string) => void;
  setSimilarityTarget: (id: string | null) => void;
  setContextMenu: (menu: ContextMenu | null) => void;
  closeContextMenu: () => void;
}

export const useUIStore = create<UIState>((set) => ({
  rhymeSidebarOpen: false,
  similarityPanelOpen: false,
  searchModalOpen: false,
  exportDialogOpen: false,
  editingSnippetId: null,
  rhymeWord: '',
  similarityTargetId: null,
  contextMenu: null,

  toggleRhymeSidebar: () => set((s) => ({ rhymeSidebarOpen: !s.rhymeSidebarOpen })),
  toggleSimilarityPanel: () => set((s) => ({ similarityPanelOpen: !s.similarityPanelOpen })),
  setSearchModalOpen: (open) => set({ searchModalOpen: open }),
  setExportDialogOpen: (open) => set({ exportDialogOpen: open }),
  setEditingSnippet: (id) => set({ editingSnippetId: id }),
  setRhymeWord: (word) => set({ rhymeWord: word, rhymeSidebarOpen: true }),
  setSimilarityTarget: (id) => set({ similarityTargetId: id, similarityPanelOpen: true }),
  setContextMenu: (menu) => set({ contextMenu: menu }),
  closeContextMenu: () => set({ contextMenu: null }),
}));
