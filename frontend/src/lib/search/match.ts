/**
 * Search matching (rest-search contract §3, the same on the backend and Android): the query is NFC
 * normalised, trimmed, split on whitespace into lowercased terms; a text matches when every term is a
 * substring of its display text (rest marks stripped), NFC normalised and lowercased. No terms match
 * everything (the filter is off).
 */
import { displayText } from '@/lib/grid/restMarks';

export interface SearchQuery {
  /** Lowercased, NFC, non-empty; empty means no search. */
  readonly terms: readonly string[];
}

export const EMPTY_SEARCH: SearchQuery = Object.freeze({ terms: Object.freeze([]) as readonly string[] });

export function parseSearchQuery(raw: string): SearchQuery {
  const terms = raw
    .normalize('NFC')
    .trim()
    .split(/\s+/)
    .filter((t) => t.length > 0)
    .map((t) => t.toLowerCase());
  return terms.length === 0 ? EMPTY_SEARCH : { terms };
}

export function isSearchActive(query: SearchQuery): boolean {
  return query.terms.length > 0;
}

/** The text search terms are matched against: display text, NFC, lowercased. */
export function searchableText(text: string): string {
  return displayText(text).normalize('NFC').toLowerCase();
}

export function matchesSearch(query: SearchQuery, text: string): boolean {
  if (query.terms.length === 0) return true;
  const haystack = searchableText(text);
  return query.terms.every((term) => haystack.includes(term));
}
