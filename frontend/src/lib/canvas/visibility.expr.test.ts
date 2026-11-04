import { describe, it, expect } from 'vitest';
import { isSnippetVisible } from './visibility';
import { snippet, tag } from '@/lib/merge/fixtures';
import { parse } from '@/lib/tagExpr';

function expr(text: string) {
  const p = parse(text);
  if (!p.ok) throw new Error(p.error.code);
  return p.expr;
}

const chorusDraft = snippet('a', 'x', { snippet_type: 'chorus', tags: [tag('t1', 'Draft'), tag('t2', 'late night')] });
const verseLove = snippet('b', 'y', { snippet_type: 'verse', tags: [tag('t3', 'love')] });

describe('isSnippetVisible with expressions', () => {
  it('matches tag names case-insensitively, not ids', () => {
    expect(isSnippetVisible(chorusDraft, expr('draft'))).toBe(true);
    expect(isSnippetVisible(chorusDraft, expr('t1'))).toBe(false);
  });

  it('addresses the snippet type', () => {
    expect(isSnippetVisible(chorusDraft, expr('type:chorus'))).toBe(true);
    expect(isSnippetVisible(verseLove, expr('type:chorus'))).toBe(false);
  });

  it('combines and negates', () => {
    const e = expr('type:verse & !draft | "late night"');
    expect(isSnippetVisible(verseLove, e)).toBe(true);
    expect(isSnippetVisible(chorusDraft, e)).toBe(true);
    expect(isSnippetVisible(verseLove, expr('love & night'))).toBe(false);
  });
});
