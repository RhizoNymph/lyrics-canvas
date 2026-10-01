import { describe, it, expect } from 'vitest';
import { moveChunk } from './moveChunk';
import type { GridCell, GridLine } from '@/types';

const c = (syllable: string, word_index: number): GridCell => ({ syllable, word_index, word: undefined });
const rest = (): GridCell => ({ rest: true });
const line = (cells: GridCell[], raw_text = ''): GridLine => ({ cells, syllable_count: cells.filter((x) => !x.rest).length, raw_text });
const syls = (l: GridLine) => l.cells.map((x) => (x.rest ? '·' : x.syllable));
const idx = (l: GridLine) => l.cells.map((x) => x.word_index);

describe('moveChunk', () => {
  // "hel lo · world"
  const base = line([c('hel', 0), c('lo', 0), rest(), c('world', 1)], 'hello world');

  it('moves a chunk later past a rest (shifts timing)', () => {
    const out = moveChunk(base, 0, 2, 1); // remove [hel lo] -> [· world], insert at 1
    expect(syls(out)).toEqual(['·', 'hel', 'lo', 'world']);
    expect(idx(out)).toEqual([undefined, 0, 0, 1]);
  });

  it('moves a whole word past another word and renumbers', () => {
    const out = moveChunk(base, 3, 4, 0); // world to the front
    expect(syls(out)).toEqual(['world', 'hel', 'lo', '·']);
    expect(idx(out)).toEqual([0, 1, 1, undefined]);
  });

  it('keeps one index per word when a syllable is dragged past another word', () => {
    const out = moveChunk(base, 1, 2, 3); // "lo" after world
    expect(syls(out)).toEqual(['hel', '·', 'world', 'lo']);
    expect(idx(out)).toEqual([0, undefined, 1, 0]);
  });

  it('recomputes syllable_count from non-rest cells', () => {
    const out = moveChunk(base, 2, 3, 0);
    expect(out.syllable_count).toBe(3);
  });

  it('clamps the target into range', () => {
    expect(syls(moveChunk(base, 0, 1, 99))).toEqual(['lo', '·', 'world', 'hel']);
    expect(syls(moveChunk(base, 3, 4, -5))).toEqual(['world', 'hel', 'lo', '·']);
  });

  it('is a no-op for an empty or out-of-range chunk', () => {
    expect(moveChunk(base, 2, 2, 0)).toBe(base);
    expect(moveChunk(base, 5, 7, 0)).toBe(base);
    expect(moveChunk(base, -1, 0, 0)).toBe(base);
  });

  it('moving to the same place keeps the order', () => {
    expect(syls(moveChunk(base, 1, 3, 1))).toEqual(syls(base));
  });

  it('does not mutate the input line', () => {
    moveChunk(base, 0, 1, 3);
    expect(syls(base)).toEqual(['hel', 'lo', '·', 'world']);
  });

  it('leaves raw_text untouched (text is rebuilt separately)', () => {
    expect(moveChunk(base, 3, 4, 0).raw_text).toBe('hello world');
  });
});
