/**
 * Keeping the local undo/redo stacks consistent with server-side changes that are not undoable
 * (transfer, remove from this canvas, delete everywhere). See docs/features/web_snippet_placements.md.
 */
import type { Group, Snippet } from '@/types';

/** One undo/redo entry: the whole snippet and group maps of the open canvas. */
export interface HistorySnapshot {
  snippets: Map<string, Snippet>;
  groups: Map<string, Group>;
}

/**
 * Drop every snapshot that holds any of `ids`, so undo/redo can never put a snippet the server removed from
 * this canvas back on it. The remaining snapshots keep their order. Same array when nothing is dropped.
 */
export function dropSnapshotsWith(stack: HistorySnapshot[], ids: ReadonlySet<string>): HistorySnapshot[] {
  if (ids.size === 0) return stack;
  const kept = stack.filter((snap) => ![...ids].some((id) => snap.snippets.has(id)));
  return kept.length === stack.length ? stack : kept;
}

/**
 * Apply `update` to the snippets `ids` in every snapshot holding them (e.g. a new entry in `canvases` after
 * "Also show on"), so undo never brings back stale shared data. Same array when nothing changes.
 */
export function mapSnapshotSnippets(
  stack: HistorySnapshot[],
  ids: ReadonlySet<string>,
  update: (snippet: Snippet) => Snippet,
): HistorySnapshot[] {
  let changed = false;
  const next = stack.map((snap) => {
    let snippets: Map<string, Snippet> | null = null;
    for (const id of ids) {
      const current = snap.snippets.get(id);
      if (!current) continue;
      const updated = update(current);
      if (updated === current) continue;
      snippets ??= new Map(snap.snippets);
      snippets.set(id, updated);
    }
    if (snippets === null) return snap;
    changed = true;
    return { ...snap, snippets };
  });
  return changed ? next : stack;
}
