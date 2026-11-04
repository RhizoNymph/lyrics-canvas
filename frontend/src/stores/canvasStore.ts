import { create } from 'zustand';
import { canvasApi, snippetApi, groupApi } from '@/api/client';
import type { Snippet, Group, CanvasDetail, SnippetType, GridMode, MergeResponse } from '@/types';
import type { SnippetMap } from '@/lib/tags/snippetTags';
import type { DerivedFrom } from '@/types/rhyme';
import { MergeError, toMergeError } from '@/lib/merge';
import type { MergeMode, MergeResult } from '@/lib/merge';
import type { ViewSize } from '@/lib/rhyme/geometry';

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
  /** Size of the canvas element when CanvasViewport last measured it; null until the canvas is shown. */
  canvasViewSize: ViewSize | null;

  // Data
  snippets: Map<string, Snippet>;
  groups: Map<string, Group>;

  // Selection (tags and the tag filter live in tagStore / tagFilterStore)
  selectedSnippetIds: Set<string>;

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
  setCanvasViewSize: (width: number, height: number) => void;

  // Snippet actions
  addSnippet: (position_x: number, position_y: number, snippet_type?: SnippetType) => Promise<void>;
  /** Create a snippet from text (rhyme-view promotion). Resolves null when no canvas is loaded. */
  addSnippetFromText: (text: string, x: number, y: number, derived_from?: DerivedFrom) => Promise<Snippet | null>;
  updateSnippet: (id: string, data: Partial<Snippet>) => Promise<void>;
  /** Insert or replace a snippet the server returned (e.g. a voice take), with an undo snapshot. */
  applySnippet: (snippet: Snippet) => void;
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

  /**
   * Rewrite the tags embedded in snippets (link, unlink, rename, recolor, delete), in the live map and
   * in every undo/redo snapshot, so undo never resurrects a tag state the server no longer has.
   * No undo entry: tag links are not part of the local history.
   */
  updateEmbeddedTags: (update: (snippets: SnippetMap) => SnippetMap) => void;

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
  canvasViewSize: null,
  snippets: new Map(),
  groups: new Map(),
  selectedSnippetIds: new Set(),
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
    set({
      canvasId: data.id,
      canvasTitle: data.title,
      viewport: data.viewport_state || { x: 0, y: 0, zoom: 1 },
      snippets,
      groups,
      selectedSnippetIds: new Set(),
      past: [],
      future: [],
    });
  },

  setViewport: (v) => {
    set((state) => ({ viewport: { ...state.viewport, ...v } }));
  },

  setCanvasViewSize: (width, height) => set({ canvasViewSize: { width, height } }),

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

  applySnippet: (snippet) => {
    if (snippet.canvas_id !== get().canvasId) return;
    const next = new Map(get().snippets);
    next.set(snippet.id, snippet);
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

  updateEmbeddedTags: (update) => {
    const { snippets, past, future } = get();
    const next = update(snippets);
    const patchSnapshot = (snap: { snippets: Map<string, Snippet>; groups: Map<string, Group> }) => {
      const patched = update(snap.snippets);
      return patched === snap.snippets ? snap : { ...snap, snippets: new Map(patched) };
    };
    set({
      ...(next !== snippets ? { snippets: new Map(next) } : {}),
      past: past.map(patchSnapshot),
      future: future.map(patchSnapshot),
    });
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
