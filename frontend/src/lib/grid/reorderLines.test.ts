import { describe, it, expect } from 'vitest';
import { reorderLines } from './reorderLines';
import type { GridLine } from '@/types';

const l = (t: string): GridLine => ({ cells: [{ syllable: t, word_index: 0 }], syllable_count: 1, raw_text: t });
const grid = { lines: [l('a'), l('b'), l('c'), l('d')] };
const texts = (g: { lines: GridLine[] }) => g.lines.map((x) => x.raw_text);

describe('reorderLines', () => {
  it('moves a line down', () => {
    expect(texts(reorderLines(grid, 0, 1))).toEqual(['b', 'a', 'c', 'd']);
    expect(texts(reorderLines(grid, 0, 3))).toEqual(['b', 'c', 'd', 'a']);
  });

  it('moves a line up', () => {
    expect(texts(reorderLines(grid, 3, 0))).toEqual(['d', 'a', 'b', 'c']);
    expect(texts(reorderLines(grid, 2, 1))).toEqual(['a', 'c', 'b', 'd']);
  });

  it('is a no-op for same or out-of-range indexes', () => {
    expect(reorderLines(grid, 1, 1)).toBe(grid);
    expect(reorderLines(grid, 4, 0)).toBe(grid);
    expect(reorderLines(grid, 0, -1)).toBe(grid);
  });

  it('does not mutate the input', () => {
    reorderLines(grid, 0, 3);
    expect(texts(grid)).toEqual(['a', 'b', 'c', 'd']);
  });
});
