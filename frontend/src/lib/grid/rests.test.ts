import { describe, it, expect } from 'vitest';
import { insertRest, removeCell } from './rests';
import type { GridCell, GridLine } from '@/types';

const c = (syllable: string, word_index: number): GridCell => ({ syllable, word_index });
const base: GridLine = { cells: [c('hel', 0), c('lo', 0), c('world', 1)], syllable_count: 3, raw_text: 'hello world' };
const syls = (l: GridLine) => l.cells.map((x) => (x.rest ? '·' : x.syllable));

describe('insertRest', () => {
  it('inserts a rest before the given cell', () => {
    expect(syls(insertRest(base, 2))).toEqual(['hel', 'lo', '·', 'world']);
    expect(syls(insertRest(base, 0))).toEqual(['·', 'hel', 'lo', 'world']);
  });

  it('appends when at is past the end and clamps negatives', () => {
    expect(syls(insertRest(base, 10))).toEqual(['hel', 'lo', 'world', '·']);
    expect(syls(insertRest(base, -3))).toEqual(['·', 'hel', 'lo', 'world']);
  });

  it('keeps syllable_count and word indexes', () => {
    const out = insertRest(base, 1);
    expect(out.syllable_count).toBe(3);
    expect(out.cells.map((x) => x.word_index)).toEqual([0, undefined, 0, 1]);
  });
});

describe('removeCell', () => {
  it('removes a rest without touching the count', () => {
    const withRest = insertRest(base, 1);
    const out = removeCell(withRest, 1);
    expect(syls(out)).toEqual(['hel', 'lo', 'world']);
    expect(out.syllable_count).toBe(3);
  });

  it('removing a syllable recounts and renumbers words', () => {
    const out = removeCell(base, 2);
    expect(syls(out)).toEqual(['hel', 'lo']);
    expect(out.syllable_count).toBe(2);
    const out2 = removeCell(removeCell(base, 0), 0);
    expect(out2.cells.map((x) => x.word_index)).toEqual([0]);
  });

  it('drops the surface word from the remaining syllables of a trimmed word', () => {
    const worded: GridLine = { ...base, cells: base.cells.map((x) => ({ ...x, word: x.word_index === 0 ? 'hello' : 'world' })) };
    const out = removeCell(worded, 0);
    expect(out.cells.map((x) => x.word)).toEqual([undefined, 'world']);
  });

  it('is a no-op out of range', () => {
    expect(removeCell(base, 3)).toBe(base);
    expect(removeCell(base, -1)).toBe(base);
  });
});
