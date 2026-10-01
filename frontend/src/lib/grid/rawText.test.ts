import { describe, it, expect } from 'vitest';
import { rebuildRawText, rebuildRawTextAll, withRebuiltText } from './rawText';
import type { GridCell, GridLine } from '@/types';

const c = (syllable: string, word_index: number, word?: string): GridCell => (word === undefined ? { syllable, word_index } : { syllable, word_index, word });
const rest = (): GridCell => ({ rest: true });
const line = (cells: GridCell[], raw_text = 'stale'): GridLine => ({ cells, syllable_count: 0, raw_text });

describe('rebuildRawText', () => {
  it('joins words in order of first appearance, concatenating syllables', () => {
    expect(rebuildRawText(line([c('world', 0), c('hel', 1), c('lo', 1)]))).toBe('world hello');
  });

  it('prefers the surface word when present (API splits are approximate)', () => {
    expect(rebuildRawText(line([c('hul', 0, 'hullabaloo,'), c('lab', 0, 'hullabaloo,'), c('alo', 0, 'hullabaloo,'), c('o', 0, 'hullabaloo,'), c('yo', 1, 'yo')]))).toBe('hullabaloo, yo');
  });

  it('skips rests and keeps a word together when its syllables are interleaved', () => {
    expect(rebuildRawText(line([c('hel', 0), rest(), c('world', 1), c('lo', 0), rest()]))).toBe('hello world');
  });

  it('treats cells without a word_index as their own words', () => {
    expect(rebuildRawText(line([{ syllable: 'a' }, c('b', 0), { syllable: 'c' }]))).toBe('a b c');
  });

  it('drops words that have no text', () => {
    expect(rebuildRawText(line([c('', 0), c('x', 1)]))).toBe('x');
    expect(rebuildRawText(line([rest(), rest()]))).toBe('');
  });

  it('withRebuiltText writes the text into raw_text', () => {
    const out = withRebuiltText(line([c('b', 1), c('a', 0)]));
    expect(out.raw_text).toBe('b a');
  });
});

describe('rebuildRawTextAll', () => {
  it('joins lines with newlines', () => {
    const grid = { lines: [line([c('one', 0)]), line([c('two', 0), c('three', 1)])] };
    expect(rebuildRawTextAll(grid)).toBe('one\ntwo three');
  });
});
