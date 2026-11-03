import { describe, it, expect } from 'vitest';
import { isSnippetVisible, visibleSnippets } from './visibility';
import { snippet, tag } from '@/lib/merge/fixtures';
import { parse } from '@/lib/tagExpr';
import type { TagExprOrEmpty } from '@/lib/tagExpr';

const a = snippet('a', 'x', { tags: [tag('t1')] });
const b = snippet('b', 'y', { tags: [tag('t2')] });
const c = snippet('c', 'z');

function expr(text: string): TagExprOrEmpty {
  const p = parse(text);
  if (!p.ok) throw new Error(p.error.code);
  return p.expr;
}

describe('isSnippetVisible', () => {
  it('shows everything when no filter is active', () => {
    expect([a, b, c].every((s) => isSnippetVisible(s, null))).toBe(true);
  });

  it('shows a snippet carrying any active tag', () => {
    expect(isSnippetVisible(a, expr('t1'))).toBe(true);
    expect(isSnippetVisible(a, expr('t2 | t1'))).toBe(true);
  });

  it('hides snippets without an active tag, including untagged ones', () => {
    expect(isSnippetVisible(b, expr('t1'))).toBe(false);
    expect(isSnippetVisible(c, expr('t1'))).toBe(false);
  });
});

describe('visibleSnippets', () => {
  it('keeps input order and drops hidden snippets', () => {
    const all = new Map([a, b, c].map((s) => [s.id, s]));
    expect(visibleSnippets(all, expr('t2')).map((s) => s.id)).toEqual(['b']);
    expect(visibleSnippets(all, null).map((s) => s.id)).toEqual(['a', 'b', 'c']);
  });
});
