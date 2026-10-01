import { describe, it, expect } from 'vitest';
import { rebaseWordIndexes, countSyllables, attachWords, attachWordsAll } from './wordIndex';
import type { GridCell, GridLine } from '@/types';

const c = (syllable: string, word_index: number, extra: Partial<GridCell> = {}): GridCell => ({ syllable, word_index, ...extra });
const rest = (): GridCell => ({ rest: true });

describe('rebaseWordIndexes', () => {
  it('renumbers by order of first appearance', () => {
    const out = rebaseWordIndexes([c('world', 1), c('hel', 0), c('lo', 0)]);
    expect(out.map((x) => x.word_index)).toEqual([0, 1, 1]);
  });

  it('keeps one index per word even when its syllables are interleaved', () => {
    const out = rebaseWordIndexes([c('hel', 0), c('world', 1), c('lo', 0)]);
    expect(out.map((x) => x.word_index)).toEqual([0, 1, 0]);
  });

  it('closes gaps left by removed words', () => {
    const out = rebaseWordIndexes([c('a', 0), c('c', 2), c('d', 5)]);
    expect(out.map((x) => x.word_index)).toEqual([0, 1, 2]);
  });

  it('skips rests and cells without a word_index, leaving them untouched', () => {
    const out = rebaseWordIndexes([rest(), c('b', 3), { syllable: 'x' }, c('a', 1)]);
    expect(out[0]).toEqual({ rest: true });
    expect(out[1].word_index).toBe(0);
    expect(out[2]).toEqual({ syllable: 'x' });
    expect(out[3].word_index).toBe(1);
  });

  it('does not mutate its input', () => {
    const input = [c('b', 3), c('a', 1)];
    rebaseWordIndexes(input);
    expect(input.map((x) => x.word_index)).toEqual([3, 1]);
  });
});

describe('countSyllables', () => {
  it('counts non-rest cells', () => {
    expect(countSyllables([c('a', 0), rest(), c('b', 1), rest()])).toBe(2);
    expect(countSyllables([])).toBe(0);
  });
});

describe('attachWords', () => {
  it('fills word from raw_text by word_index', () => {
    const line: GridLine = { cells: [c('hel', 0), c('lo', 0), rest(), c('world,', 1)], syllable_count: 3, raw_text: 'hello  world,' };
    const out = attachWords(line);
    expect(out.cells.map((x) => x.word)).toEqual(['hello', 'hello', undefined, 'world,']);
  });

  it('keeps an existing word and ignores out-of-range indexes', () => {
    const line: GridLine = { cells: [c('a', 0, { word: 'kept' }), c('b', 7)], syllable_count: 2, raw_text: 'alpha' };
    const out = attachWords(line);
    expect(out.cells[0].word).toBe('kept');
    expect(out.cells[1].word).toBeUndefined();
  });

  it('attachWordsAll maps every line', () => {
    const grid = { lines: [{ cells: [c('one', 0)], syllable_count: 1, raw_text: 'one' }, { cells: [c('two', 0)], syllable_count: 1, raw_text: 'two' }] };
    expect(attachWordsAll(grid).lines.map((l) => l.cells[0].word)).toEqual(['one', 'two']);
  });
});
