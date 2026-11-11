/** Split a result excerpt into plain and matched runs for highlighting (case-insensitive, all terms). */
import type { SearchQuery } from './match';

export interface HighlightSegment {
  readonly text: string;
  readonly match: boolean;
}

export function highlightSegments(text: string, query: SearchQuery): HighlightSegment[] {
  if (text.length === 0) return [];
  const lower = text.normalize('NFC').toLowerCase();
  // Offsets are only meaningful while normalising and lowercasing keep the length; otherwise show plain.
  if (lower.length !== text.length || query.terms.length === 0) return [{ text, match: false }];
  const marked = new Array<boolean>(text.length).fill(false);
  for (const term of query.terms) {
    for (let at = lower.indexOf(term); at >= 0; at = lower.indexOf(term, at + 1)) {
      for (let i = at; i < at + term.length; i++) marked[i] = true;
    }
  }
  const segments: HighlightSegment[] = [];
  let start = 0;
  for (let i = 1; i <= text.length; i++) {
    if (i === text.length || marked[i] !== marked[start]) {
      segments.push({ text: text.slice(start, i), match: marked[start] });
      start = i;
    }
  }
  return segments;
}
