import { create } from 'zustand';
import { canvasApi, snippetApi, groupApi, tagApi } from '@/api/client';
import type { Snippet, Group, Tag, CanvasDetail, SnippetType, GridMode, MergeResponse } from '@/types';
import type { DerivedFrom } from '@/types/rhyme';
import { MergeError, toMergeError } from '@/lib/merge';
import type { MergeMode, MergeResult } from '@/lib/merge';

interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

interface CanvasState {
  // Canvas metadata
  canvasId: string | null;
  canvasTitle: string;

  // Viewport
  viewport: Viewport;

  // Data
  snippets: Map<string, Snippet>;
  groups: Map<string, Group>;
  tags: Map<string, Tag>;

  // Selection / filtering
  selectedSnippetIds: Set<string>;
  activeTagFilters: Set<string>;

  // Undo/redo
  past: Array<{ snippets: Map<string, Snippet>; groups: Map<string, Group> }>;
  future: Array<{ snippets: Map<string, Snippet>; groups: Map<string, Group> }>;

  // Per-snippet scale factors (visual zoom)
  snippetScales: Map<string, number>;

  // t-SNE mode
  tsneMode: boolean;
  savedPositions: Map<string, { x: number; y: number }> | null;

  /** The last merge failure, or null. */
  mergeError: MergeError | null;

  // Actions
  loadCanvas: (id: string) => Promise<void>;
  setViewport: (v: Partial<Viewport>) => void;

  // Snippet actions
  addSnippet: (position_x: number, position_y: number, snippet_type?: SnippetType) => Promise<void>;
  /** Create a snippet from text (rhyme-view promotion). Resolves null when no canvas is loaded. */
  addSnippetFromText: (text: string, x: number, y: number, derived_from?: DerivedFrom) => Promise<Snippet | null>;
  updateSnippet: (id: string, data: Partial<Snippet>) => Promise<void>;
  deleteSnippet: (id: string) => Promise<void>;
  moveSnippet: (id: string, x: number, y: number) => void;
  commitPositions: () => Promise<void>;
  /**
   * Replace the selected snippets (two or more) with one merged snippet via the server
   * merge endpoint (canvas-position order, first block's position). Never throws.
   */
  mergeSelected: (mode: MergeMode) => Promise<MergeResult>;
  clearMergeError: () => void;

  // Selection
  selectSnippet: (id: string, multi?: boolean) => void;
  clearSelection: () => void;
  /** Replace the selection (ids not on this canvas are dropped). Used by list mode. */
  setSelection: (ids: ReadonlySet<string>) => void;

  // Scaling
  setSnippetScale: (id: string, scale: number) => void;

  // Groups
  createGroup: (name: string) => Promise<void>;
  deleteGroup: (id: string) => Promise<void>;
  moveGroup: (groupId: string, dx: number, dy: number) => void;

  // Tags
  createTag: (name: string, color: string) => Promise<string | null>;
  updateTag: (id: string, data: { name?: string; color?: string }) => Promise<void>;
  deleteTag: (id: string) => Promise<void>;
  toggleTagFilter: (id: string) => void;
  addTagToSnippet: (snippetId: string, tagId: string) => Promise<void>;
  removeTagFromSnippet: (snippetId: string, tagId: string) => Promise<void>;

  // Undo/redo
  pushUndoState: () => void;
  undo: () => void;
  redo: () => void;

  // t-SNE
  enterTSNE: (positions: Array<{ snippet_id: string; x: number; y: number }>) => void;
  exitTSNE: () => void;
}

export const useCanvasStore = create<CanvasState>((set, get) => ({
  canvasId: null,
  canvasTitle: '',
  viewport: { x: 0, y: 0, zoom: 1 },
  snippets: new Map(),
  groups: new Map(),
  tags: new Map(),
  selectedSnippetIds: new Set(),
  activeTagFilters: new Set(),
  snippetScales: new Map(),
  past: [],
  future: [],
  tsneMode: false,
  savedPositions: null,
  mergeError: null,

  loadCanvas: async (id) => {
    const res = await canvasApi.get(id);
    const data: CanvasDetail = res.data;
    const snippets = new Map<string, Snippet>();
    data.snippets.forEach((s) => snippets.set(s.id, s));
    const groups = new Map<string, Group>();
    data.groups.forEach((g) => groups.set(g.id, g));
    const tags = new Map<string, Tag>();
    data.tags.forEach((t) => tags.set(t.id, t));
    set({
      canvasId: data.id,
      canvasTitle: data.title,
      viewport: data.viewport_state || { x: 0, y: 0, zoom: 1 },
      snippets,
      groups,
      tags,
      selectedSnippetIds: new Set(),
      past: [],
      future: [],
    });
  },

  setViewport: (v) => {
    set((state) => ({ viewport: { ...state.viewport, ...v } }));
  },

  addSnippet: async (position_x, position_y, snippet_type = 'freestyle') => {
    const { canvasId, snippets } = get();
    if (!canvasId) return;
    const res = await snippetApi.create(canvasId, { position_x, position_y, snippet_type });
    const snippet: Snippet = res.data;
    const next = new Map(snippets);
    next.set(snippet.id, snippet);
    get().pushUndoState();
    set({ snippets: next });
  },

  addSnippetFromText: async (text, x, y, derived_from) => {
    const { canvasId, snippets } = get();
    if (!canvasId) return null;
    const res = await snippetApi.create(canvasId, {
      raw_text: text,
      position_x: x,
      position_y: y,
      snippet_type: 'freestyle',
      derived_from,
    });
    const snippet: Snippet = res.data;
    const next = new Map(snippets);
    next.set(snippet.id, snippet);
    get().pushUndoState();
    set({ snippets: next });
    return snippet;
  },

  updateSnippet: async (id, data) => {
    const { snippets } = get();
    const res = await snippetApi.update(id, data as Record<string, unknown>);
    const updated: Snippet = res.data;
    const next = new Map(snippets);
    next.set(id, updated);
    get().pushUndoState();
    set({ snippets: next });
  },

  deleteSnippet: async (id) => {
    const { snippets, selectedSnippetIds } = get();
    await snippetApi.delete(id);
    const next = new Map(snippets);
    next.delete(id);
    const sel = new Set(selectedSnippetIds);
    sel.delete(id);
    get().pushUndoState();
    set({ snippets: next, selectedSnippetIds: sel });
  },

  moveSnippet: (id, x, y) => {
    const { snippets } = get();
    const snippet = snippets.get(id);
    if (!snippet) return;
    const next = new Map(snippets);
    next.set(id, { ...snippet, position_x: x, position_y: y });
    set({ snippets: next });
  },

  commitPositions: async () => {
    const { snippets } = get();
    const updates = Array.from(snippets.values()).map((s) => ({
      id: s.id,
      position_x: s.position_x,
      position_y: s.position_y,
    }));
    await snippetApi.batchPosition(updates);
  },

  mergeSelected: async (mode) => {
    const fail = (error: MergeError): MergeResult => {
      set({ mergeError: error });
      return { ok: false, error };
    };
    const { canvasId, snippets, selectedSnippetIds } = get();
    if (!canvasId) return fail(new MergeError('no_canvas', 'No canvas is loaded'));
    const ids = [...selectedSnippetIds].filter((id) => snippets.has(id));
    if (ids.length < 2) {
      return fail(new MergeError('too_few_selected', 'Select at least two snippets to merge', null, ids));
    }

    let merged: MergeResponse;
    try {
      merged = (await canvasApi.merge(canvasId, ids, mode)).data;
    } catch (err) {
      // The server merge is one transaction: a failure changed nothing.
      return fail(toMergeError(err, 'merge_failed', ids));
    }

    const next = new Map(get().snippets);
    for (const id of merged.deleted_ids) next.delete(id);
    next.set(merged.snippet.id, merged.snippet);
    get().pushUndoState();
    set({ snippets: next, selectedSnippetIds: new Set([merged.snippet.id]), mergeError: null });
    return { ok: true, snippet: merged.snippet };
  },

  clearMergeError: () => set({ mergeError: null }),

  selectSnippet: (id, multi = false) => {
    const { selectedSnippetIds } = get();
    const next = multi ? new Set(selectedSnippetIds) : new Set<string>();
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    set({ selectedSnippetIds: next });
  },

  clearSelection: () => set({ selectedSnippetIds: new Set() }),

  setSelection: (ids) => {
    const { snippets } = get();
    set({ selectedSnippetIds: new Set([...ids].filter((id) => snippets.has(id))) });
  },

  setSnippetScale: (id, scale) => {
    const next = new Map(get().snippetScales);
    next.set(id, Math.max(0.3, Math.min(4, scale)));
    set({ snippetScales: next });
  },

  createGroup: async (name) => {
    const { canvasId, groups } = get();
    if (!canvasId) return;
    const res = await groupApi.create(canvasId, name);
    const group: Group = res.data;
    const next = new Map(groups);
    next.set(group.id, group);
    set({ groups: next });
  },

  deleteGroup: async (id) => {
    const { groups } = get();
    await groupApi.delete(id);
    const next = new Map(groups);
    next.delete(id);
    set({ groups: next });
  },

  moveGroup: (groupId, dx, dy) => {
    const { groups, snippets } = get();
    const group = groups.get(groupId);
    if (!group) return;

    const nextGroups = new Map(groups);
    nextGroups.set(groupId, {
      ...group,
      position_x: group.position_x + dx,
      position_y: group.position_y + dy,
    });

    const nextSnippets = new Map(snippets);
    for (const sid of group.snippet_ids) {
      const s = snippets.get(sid);
      if (s) {
        nextSnippets.set(sid, {
          ...s,
          position_x: s.position_x + dx,
          position_y: s.position_y + dy,
        });
      }
    }

    set({ groups: nextGroups, snippets: nextSnippets });
  },

  createTag: async (name, color) => {
    const { canvasId, tags } = get();
    if (!canvasId) return null;
    const res = await tagApi.create(canvasId, name, color);
    const tag: Tag = res.data;
    const next = new Map(tags);
    next.set(tag.id, tag);
    set({ tags: next });
    return tag.id;
  },

  updateTag: async (id, data) => {
    const res = await tagApi.update(id, data);
    const updated = res.data;
    const { tags } = get();
    const next = new Map(tags);
    next.set(id, updated);
    // Also update the tag in any snippets that reference it
    const { snippets } = get();
    const nextSnippets = new Map(snippets);
    let changed = false;
    for (const [sid, snippet] of nextSnippets) {
      const idx = snippet.tags.findIndex((t) => t.id === id);
      if (idx >= 0) {
        const newTags = [...snippet.tags];
        newTags[idx] = updated;
        nextSnippets.set(sid, { ...snippet, tags: newTags });
        changed = true;
      }
    }
    set({ tags: next, ...(changed ? { snippets: nextSnippets } : {}) });
  },

  deleteTag: async (id) => {
    const { tags, snippets, activeTagFilters } = get();
    await tagApi.delete(id);
    const nextTags = new Map(tags);
    nextTags.delete(id);
    // Remove from all snippets' tag arrays
    const nextSnippets = new Map(snippets);
    let changed = false;
    for (const [sid, snippet] of nextSnippets) {
      if (snippet.tags.some((t) => t.id === id)) {
        nextSnippets.set(sid, { ...snippet, tags: snippet.tags.filter((t) => t.id !== id) });
        changed = true;
      }
    }
    // Remove from active filters
    const nextFilters = new Set(activeTagFilters);
    nextFilters.delete(id);
    set({ tags: nextTags, activeTagFilters: nextFilters, ...(changed ? { snippets: nextSnippets } : {}) });
  },

  toggleTagFilter: (id) => {
    const { activeTagFilters } = get();
    const next = new Set(activeTagFilters);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    set({ activeTagFilters: next });
  },

  addTagToSnippet: async (snippetId, tagId) => {
    await tagApi.addToSnippet(snippetId, tagId);
    const { canvasId } = get();
    if (canvasId) await get().loadCanvas(canvasId);
  },

  removeTagFromSnippet: async (snippetId, tagId) => {
    await tagApi.removeFromSnippet(snippetId, tagId);
    const { canvasId } = get();
    if (canvasId) await get().loadCanvas(canvasId);
  },

  pushUndoState: () => {
    const { snippets, groups, past } = get();
    const snapshot = {
      snippets: new Map(snippets),
      groups: new Map(groups),
    };
    set({ past: [...past.slice(-50), snapshot], future: [] });
  },

  undo: () => {
    const { past, snippets, groups } = get();
    if (past.length === 0) return;
    const prev = past[past.length - 1];
    const currentSnapshot = { snippets: new Map(snippets), groups: new Map(groups) };
    set({
      snippets: prev.snippets,
      groups: prev.groups,
      past: past.slice(0, -1),
      future: [currentSnapshot, ...get().future],
    });
  },

  redo: () => {
    const { future, snippets, groups } = get();
    if (future.length === 0) return;
    const next = future[0];
    const currentSnapshot = { snippets: new Map(snippets), groups: new Map(groups) };
    set({
      snippets: next.snippets,
      groups: next.groups,
      future: future.slice(1),
      past: [...get().past, currentSnapshot],
    });
  },

  enterTSNE: (positions) => {
    const { snippets } = get();
    const saved = new Map<string, { x: number; y: number }>();
    snippets.forEach((s, id) => saved.set(id, { x: s.position_x, y: s.position_y }));

    const next = new Map(snippets);
    for (const pos of positions) {
      const s = next.get(pos.snippet_id);
      if (s) {
        next.set(pos.snippet_id, { ...s, position_x: pos.x, position_y: pos.y });
      }
    }
    set({ tsneMode: true, savedPositions: saved, snippets: next });
  },

  exitTSNE: () => {
    const { snippets, savedPositions } = get();
    if (!savedPositions) {
      set({ tsneMode: false });
      return;
    }
    const next = new Map(snippets);
    savedPositions.forEach((pos, id) => {
      const s = next.get(id);
      if (s) next.set(id, { ...s, position_x: pos.x, position_y: pos.y });
    });
    set({ tsneMode: false, savedPositions: null, snippets: next });
  },
}));
