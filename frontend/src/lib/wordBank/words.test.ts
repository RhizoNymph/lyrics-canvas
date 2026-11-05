import { describe, it, expect } from 'vitest';
import { parse } from '@/lib/tagExpr';
import type { TagExprOrEmpty } from '@/lib/tagExpr';
import { bw, wtag } from './fixtures';
import {
  byNewest, mergeWords, patchWordTag, removeWords, removeWordTag, selectedInOrder, tagLinkDelta, visibleWords,
} from './words';

const expr = (t: string): TagExprOrEmpty => {
  const p = parse(t);
  if (!p.ok) throw new Error(p.error.code);
  return p.expr;
};

describe('byNewest', () => {
  it('sorts created_at desc then id asc', () => {
    const words = [bw('b', 'x', 1), bw('a', 'y', 1), bw('c', 'z', 2)];
    expect([...words].sort(byNewest).map((w) => w.id)).toEqual(['c', 'a', 'b']);
  });
});

describe('mergeWords', () => {
  it('replaces words by id and adds new ones in newest-first order', () => {
    const words = [bw('b', 'two', 2), bw('a', 'one', 1)];
    const next = mergeWords(words, [bw('a', 'One', 1), bw('c', 'three', 3)]);
    expect(next.map((w) => [w.id, w.text])).toEqual([['c', 'three'], ['b', 'two'], ['a', 'One']]);
  });

  it('returns the same array when nothing comes in', () => {
    const words = [bw('a', 'one')];
    expect(mergeWords(words, [])).toBe(words);
  });
});

describe('removeWords', () => {
  it('drops the given ids', () => {
    expect(removeWords([bw('a', 'x'), bw('b', 'y')], new Set(['a'])).map((w) => w.id)).toEqual(['b']);
  });
});

describe('patchWordTag / removeWordTag', () => {
  const words = [bw('a', 'x', 0, [wtag('t1', 'love'), wtag('t2', 'zeta')]), bw('b', 'y', 0, [wtag('t2', 'zeta')]), bw('c', 'z')];

  it('replaces the embedded copy and keeps tags sorted by lower(name)', () => {
    const next = patchWordTag(words, wtag('t2', 'Alpha', '#ffffff'));
    expect(next[0].tags.map((t) => t.name)).toEqual(['Alpha', 'love']);
    expect(next[1].tags).toEqual([wtag('t2', 'Alpha', '#ffffff')]);
    expect(next[2]).toBe(words[2]);
  });

  it('returns the same array when no word carries the tag', () => {
    expect(patchWordTag(words, wtag('t9', 'n'))).toBe(words);
    expect(removeWordTag(words, 't9')).toBe(words);
  });

  it('drops the tag everywhere', () => {
    const next = removeWordTag(words, 't2');
    expect(next.map((w) => w.tags.map((t) => t.id))).toEqual([['t1'], [], []]);
  });
});

describe('visibleWords', () => {
  const words = [bw('a', 'x', 0, [wtag('t1', 'love')]), bw('b', 'y', 0, [wtag('t2', 'night')]), bw('c', 'z')];

  it('shows everything without a filter', () => {
    expect(visibleWords(words, null)).toBe(words);
  });

  it('evaluates with tag names and no type', () => {
    expect(visibleWords(words, expr('love | night')).map((w) => w.id)).toEqual(['a', 'b']);
    expect(visibleWords(words, expr('!love')).map((w) => w.id)).toEqual(['b', 'c']);
    expect(visibleWords(words, expr('type:verse')).map((w) => w.id)).toEqual([]);
    expect(visibleWords(words, expr('!type:verse')).map((w) => w.id)).toEqual(['a', 'b', 'c']);
    expect(visibleWords(words, expr('LOVE')).map((w) => w.id)).toEqual(['a']);
  });
});

describe('selectedInOrder', () => {
  it('returns the selected words in list order, not selection order', () => {
    const words = [bw('a', 'x'), bw('b', 'y'), bw('c', 'z')];
    expect(selectedInOrder(words, new Set(['c', 'a'])).map((w) => w.id)).toEqual(['a', 'c']);
  });
});

describe('tagLinkDelta', () => {
  it('counts words whose link to the tag changed', () => {
    const before = [bw('a', 'x', 0, [wtag('t1')]), bw('b', 'y')];
    const after = [bw('a', 'x', 0, [wtag('t1')]), bw('b', 'y', 0, [wtag('t1')])];
    expect(tagLinkDelta(before, after, 't1')).toBe(1);
    expect(tagLinkDelta(after, before, 't1')).toBe(-1);
  });

  it('counts a word new to the list by its state after', () => {
    expect(tagLinkDelta([], [bw('n', 'new', 0, [wtag('t1')])], 't1')).toBe(1);
  });
});
