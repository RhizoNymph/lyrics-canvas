import { describe, it, expect } from 'vitest';
import { EMPTY_SELECTION, clickKind, selectionReducer } from './selection';
import type { WordSelection } from './selection';

const order = ['a', 'b', 'c', 'd', 'e'];
const sel = (ids: string[], anchor: string | null = ids[ids.length - 1] ?? null): WordSelection => ({ ids: new Set(ids), anchor });
const ids = (s: WordSelection) => [...s.ids].sort();

describe('selectionReducer', () => {
  it('click selects only that word and sets the anchor', () => {
    const s = selectionReducer(sel(['a', 'b']), { type: 'click', id: 'c' });
    expect(ids(s)).toEqual(['c']);
    expect(s.anchor).toBe('c');
  });

  it('toggle adds or removes one word and moves the anchor', () => {
    const s1 = selectionReducer(sel(['a']), { type: 'toggle', id: 'c' });
    expect(ids(s1)).toEqual(['a', 'c']);
    expect(s1.anchor).toBe('c');
    const s2 = selectionReducer(s1, { type: 'toggle', id: 'a' });
    expect(ids(s2)).toEqual(['c']);
  });

  it('range selects from the anchor to the word in list order, either direction', () => {
    expect(ids(selectionReducer(sel(['b']), { type: 'range', id: 'd', order }))).toEqual(['b', 'c', 'd']);
    expect(ids(selectionReducer(sel(['d']), { type: 'range', id: 'b', order }))).toEqual(['b', 'c', 'd']);
  });

  it('range keeps the anchor so a second shift-click re-ranges from it', () => {
    const s1 = selectionReducer(sel(['b']), { type: 'range', id: 'e', order });
    const s2 = selectionReducer(s1, { type: 'range', id: 'c', order });
    expect(ids(s2)).toEqual(['b', 'c']);
    expect(s2.anchor).toBe('b');
  });

  it('range without a visible anchor acts as a click', () => {
    expect(ids(selectionReducer(EMPTY_SELECTION, { type: 'range', id: 'c', order }))).toEqual(['c']);
    expect(ids(selectionReducer(sel(['zz']), { type: 'range', id: 'c', order }))).toEqual(['c']);
  });

  it('add unions ids (family select all, rhyme matches)', () => {
    expect(ids(selectionReducer(sel(['a']), { type: 'add', ids: ['b', 'c'] }))).toEqual(['a', 'b', 'c']);
  });

  it('set replaces, clear empties', () => {
    expect(ids(selectionReducer(sel(['a']), { type: 'set', ids: ['d'] }))).toEqual(['d']);
    expect(selectionReducer(sel(['a']), { type: 'clear' })).toEqual(EMPTY_SELECTION);
  });

  it('prune keeps only existing ids and drops a stale anchor; unchanged returns the same object', () => {
    const s = sel(['a', 'b'], 'b');
    const pruned = selectionReducer(s, { type: 'prune', keep: new Set(['a', 'c']) });
    expect(ids(pruned)).toEqual(['a']);
    expect(pruned.anchor).toBeNull();
    expect(selectionReducer(s, { type: 'prune', keep: new Set(['a', 'b']) })).toBe(s);
  });
});

describe('clickKind', () => {
  it('maps modifiers', () => {
    expect(clickKind({ shiftKey: false, ctrlKey: false, metaKey: false })).toBe('click');
    expect(clickKind({ shiftKey: false, ctrlKey: true, metaKey: false })).toBe('toggle');
    expect(clickKind({ shiftKey: false, ctrlKey: false, metaKey: true })).toBe('toggle');
    expect(clickKind({ shiftKey: true, ctrlKey: false, metaKey: false })).toBe('range');
    expect(clickKind({ shiftKey: true, ctrlKey: true, metaKey: false })).toBe('range');
  });
});
