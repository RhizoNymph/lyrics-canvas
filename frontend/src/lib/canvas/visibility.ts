/**
 * Tag-filter visibility shared by every view of a canvas (Pixi canvas and list mode): with no filter
 * (the empty expression) everything shows; otherwise a snippet shows when the expression matches its
 * type and tag names.
 */
import type { Snippet } from '@/types';
import { evaluate } from '@/lib/tagExpr';
import type { TagExprOrEmpty } from '@/lib/tagExpr';

export function isSnippetVisible(snippet: Snippet, filter: TagExprOrEmpty): boolean {
  if (filter === null) return true;
  return evaluate(filter, { type: snippet.snippet_type, tagNames: snippet.tags.map((t) => t.name) });
}

/** The visible snippets in the map's iteration order. */
export function visibleSnippets(snippets: ReadonlyMap<string, Snippet>, filter: TagExprOrEmpty): Snippet[] {
  const all = Array.from(snippets.values());
  return filter === null ? all : all.filter((s) => isSnippetVisible(s, filter));
}
