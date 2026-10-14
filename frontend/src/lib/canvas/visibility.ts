/**
 * Tag-filter visibility shared by every view of a canvas (Pixi canvas and list mode): with no active
 * tag filter everything shows; otherwise a snippet shows when it carries at least one active tag.
 */
import type { Snippet } from '@/types';

export function isSnippetVisible(snippet: Snippet, activeTagFilters: ReadonlySet<string>): boolean {
  if (activeTagFilters.size === 0) return true;
  return snippet.tags.some((t) => activeTagFilters.has(t.id));
}

/** The visible snippets in the map's iteration order. */
export function visibleSnippets(snippets: ReadonlyMap<string, Snippet>, activeTagFilters: ReadonlySet<string>): Snippet[] {
  return Array.from(snippets.values()).filter((s) => isSnippetVisible(s, activeTagFilters));
}
