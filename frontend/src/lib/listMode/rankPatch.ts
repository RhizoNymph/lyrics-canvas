/** Applying list rank changes to a snippet map (the live canvas map or an undo/redo snapshot). */
import type { Snippet } from '@/types';

export interface RankPatch {
  id: string;
  list_rank: string;
  /** Patch only while the snippet's rank is still this (a rollback must not undo a later reorder). */
  expect?: string;
}

/** A copy with the patches applied; the same map when nothing changes. Ids not in the map are ignored. */
export function patchRanks(snippets: Map<string, Snippet>, patches: ReadonlyArray<RankPatch>): Map<string, Snippet> {
  let next: Map<string, Snippet> | null = null;
  for (const p of patches) {
    const current = (next ?? snippets).get(p.id);
    if (!current || current.list_rank === p.list_rank) continue;
    if (p.expect !== undefined && current.list_rank !== p.expect) continue;
    next ??= new Map(snippets);
    next.set(p.id, { ...current, list_rank: p.list_rank });
  }
  return next ?? snippets;
}
