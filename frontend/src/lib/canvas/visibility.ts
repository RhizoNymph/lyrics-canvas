/**
 * Visibility shared by every view of a canvas (Pixi canvas and list mode): the tag filter AND the in-canvas
 * search. With no tag filter (the empty expression) every type and tag passes; otherwise the expression
 * must match the snippet's type and tag names. With an empty search every text passes; otherwise every term
 * must be in the snippet's display text (lib/search/match.ts).
 */
import type { Snippet } from '@/types';
import { evaluate } from '@/lib/tagExpr';
import type { TagExprOrEmpty } from '@/lib/tagExpr';
import { EMPTY_SEARCH, isSearchActive, matchesSearch } from '@/lib/search/match';
import type { SearchQuery } from '@/lib/search/match';

export function isSnippetVisible(snippet: Snippet, filter: TagExprOrEmpty): boolean {
  if (filter === null) return true;
  return evaluate(filter, { type: snippet.snippet_type, tagNames: snippet.tags.map((t) => t.name) });
}

/** Tag filter AND in-canvas search. */
export function isSnippetShown(snippet: Snippet, filter: TagExprOrEmpty, search: SearchQuery): boolean {
  return isSnippetVisible(snippet, filter) && matchesSearch(search, snippet.raw_text);
}

/** The visible snippets in the map's iteration order. */
export function visibleSnippets(
  snippets: ReadonlyMap<string, Snippet>,
  filter: TagExprOrEmpty,
  search: SearchQuery = EMPTY_SEARCH,
): Snippet[] {
  const all = Array.from(snippets.values());
  if (filter === null && !isSearchActive(search)) return all;
  return all.filter((s) => isSnippetShown(s, filter, search));
}
