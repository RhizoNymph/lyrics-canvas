import { describe, it, expect } from 'vitest';
import { isSnippetShown, visibleSnippets } from './visibility';
import { parseSearchQuery } from '@/lib/search/match';
import { parse } from '@/lib/tagExpr';
import { snippet } from '@/lib/merge/fixtures';
import type { Snippet } from '@/types';

const map = (...ss: Snippet[]) => new Map(ss.map((s) => [s.id, s]));
const expr = (text: string) => {
  const r = parse(text);
  if (!r.ok) throw new Error(text);
  return r.expr;
};

describe('visibleSnippets with an in-canvas search', () => {
  const a = snippet('a', 'black_cat', { snippet_type: 'verse' });
  const b = snippet('b', 'white cat', { snippet_type: 'chorus' });
  const c = snippet('c', 'dog', { snippet_type: 'verse' });

  it('filters by display text', () => {
    expect(visibleSnippets(map(a, b, c), null, parseSearchQuery('CAT')).map((s) => s.id)).toEqual(['a', 'b']);
    expect(visibleSnippets(map(a, b, c), null, parseSearchQuery('black cat')).map((s) => s.id)).toEqual(['a']);
  });

  it('combines with the tag filter by AND', () => {
    expect(visibleSnippets(map(a, b, c), expr('type:verse'), parseSearchQuery('cat')).map((s) => s.id)).toEqual(['a']);
  });

  it('shows everything the tag filter shows when the search is empty', () => {
    expect(visibleSnippets(map(a, b, c), expr('type:verse'), parseSearchQuery('  ')).map((s) => s.id)).toEqual(['a', 'c']);
    expect(isSnippetShown(b, null, parseSearchQuery(''))).toBe(true);
  });
});
