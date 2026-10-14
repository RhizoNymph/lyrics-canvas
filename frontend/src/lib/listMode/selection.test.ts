import { describe, it, expect } from 'vitest';
import { listClick } from './selection';
import type { ListClickModifiers } from './selection';

const order = ['a', 'b', 'c', 'd', 'e'];
const none: ListClickModifiers = { shift: false, toggle: false };
const shift: ListClickModifiers = { shift: true, toggle: false };
const toggle: ListClickModifiers = { shift: false, toggle: true };

describe('listClick: plain click', () => {
  it('selects only the clicked row, anchors there, and opens the editor', () => {
    const r = listClick({ selection: new Set(['a', 'c']), anchor: 'a', order }, 'd', none);
    expect([...r.selection]).toEqual(['d']);
    expect(r.anchor).toBe('d');
    expect(r.openEditor).toBe(true);
  });
});

describe('listClick: ctrl/cmd click', () => {
  it('adds an unselected row without opening the editor', () => {
    const r = listClick({ selection: new Set(['a']), anchor: 'a', order }, 'c', toggle);
    expect([...r.selection].sort()).toEqual(['a', 'c']);
    expect(r.anchor).toBe('c');
    expect(r.openEditor).toBe(false);
  });

  it('removes a selected row', () => {
    const r = listClick({ selection: new Set(['a', 'c']), anchor: 'a', order }, 'c', toggle);
    expect([...r.selection]).toEqual(['a']);
  });

  it('does not mutate the previous selection', () => {
    const prev = new Set(['a']);
    listClick({ selection: prev, anchor: 'a', order }, 'b', toggle);
    expect([...prev]).toEqual(['a']);
  });
});

describe('listClick: shift click', () => {
  it('adds the visible range from the anchor to the clicked row (either direction)', () => {
    const down = listClick({ selection: new Set(['b']), anchor: 'b', order }, 'd', shift);
    expect([...down.selection].sort()).toEqual(['b', 'c', 'd']);
    const up = listClick({ selection: new Set(['d']), anchor: 'd', order }, 'b', shift);
    expect([...up.selection].sort()).toEqual(['b', 'c', 'd']);
    expect(down.openEditor).toBe(false);
  });

  it('keeps the anchor so further shift-clicks pivot on it', () => {
    const r = listClick({ selection: new Set(['b']), anchor: 'b', order }, 'd', shift);
    expect(r.anchor).toBe('b');
  });

  it('is additive: rows selected elsewhere stay selected', () => {
    const r = listClick({ selection: new Set(['offscreen', 'a']), anchor: 'c', order }, 'd', shift);
    expect([...r.selection].sort()).toEqual(['a', 'c', 'd', 'offscreen']);
  });

  it('toggles the clicked row like the canvas when there is no usable anchor', () => {
    const noAnchor = listClick({ selection: new Set(['a']), anchor: null, order }, 'c', shift);
    expect([...noAnchor.selection].sort()).toEqual(['a', 'c']);
    expect(noAnchor.anchor).toBe('c');
    const hidden = listClick({ selection: new Set(['a', 'c']), anchor: 'gone', order }, 'c', shift);
    expect([...hidden.selection]).toEqual(['a']);
  });
});
