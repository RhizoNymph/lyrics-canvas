import { describe, it, expect } from 'vitest';
import { menuKeyEffect, moveHighlight } from './menuNav';

describe('moveHighlight', () => {
  it('wraps both ways and starts from the ends when nothing is highlighted', () => {
    expect(moveHighlight(0, 3, 1)).toBe(1);
    expect(moveHighlight(2, 3, 1)).toBe(0);
    expect(moveHighlight(0, 3, -1)).toBe(2);
    expect(moveHighlight(-1, 3, 1)).toBe(0);
    expect(moveHighlight(-1, 3, -1)).toBe(2);
    expect(moveHighlight(0, 0, 1)).toBe(-1);
  });
});

describe('menuKeyEffect', () => {
  it('maps arrows, accept keys and escape', () => {
    expect(menuKeyEffect('ArrowDown', 0, 3)).toEqual({ kind: 'move', index: 1 });
    expect(menuKeyEffect('ArrowUp', 0, 3)).toEqual({ kind: 'move', index: 2 });
    expect(menuKeyEffect('Enter', 1, 3)).toEqual({ kind: 'accept', index: 1 });
    expect(menuKeyEffect('Tab', -1, 3)).toEqual({ kind: 'accept', index: 0 });
    expect(menuKeyEffect('Enter', 0, 0)).toEqual({ kind: 'none' });
    expect(menuKeyEffect('Escape', 0, 3)).toEqual({ kind: 'close' });
    expect(menuKeyEffect('a', 0, 3)).toEqual({ kind: 'none' });
  });
});
