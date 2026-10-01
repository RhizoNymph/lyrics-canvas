import { describe, it, expect } from 'vitest';
import { estimateSyllableCount, splitWord, localSyllabifyLine } from './localSyllabify';

describe('estimateSyllableCount', () => {
  it('counts vowel groups with the silent-e adjustment', () => {
    expect(estimateSyllableCount('hullabaloo')).toBe(4);
    expect(estimateSyllableCount('make')).toBe(1);
    expect(estimateSyllableCount('rhythm')).toBe(1);
    expect(estimateSyllableCount('123')).toBe(1);
  });
});

describe('splitWord', () => {
  it('splits into estimated chunks', () => {
    expect(splitWord('hullabaloo')).toEqual(['hul', 'lab', 'alo', 'o']);
    expect(splitWord('cat')).toEqual(['cat']);
  });
});

describe('localSyllabifyLine', () => {
  it('builds a grid line with word indexes, surface words and raw_text', () => {
    const line = localSyllabifyLine('  hello   world, ');
    expect(line.raw_text).toBe('hello   world,');
    expect(line.cells.map((c) => c.syllable)).toEqual(['hel', 'lo', 'world,']);
    expect(line.cells.map((c) => c.word_index)).toEqual([0, 0, 1]);
    expect(line.cells.map((c) => c.word)).toEqual(['hello', 'hello', 'world,']);
    expect(line.syllable_count).toBe(3);
  });

  it('returns an empty line for blank text', () => {
    expect(localSyllabifyLine('   ')).toEqual({ cells: [], syllable_count: 0, raw_text: '' });
  });
});
