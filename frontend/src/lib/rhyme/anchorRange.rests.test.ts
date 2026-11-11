import { describe, it, expect } from 'vitest';
import { lineWordCount, lineWords, rangeFits, rangeText, sliceGridLine } from './anchorRange';
import type { GridLine, Snippet } from '@/types';

// "orange _ door_hinge": the text carries its rests as _.
const restLine: GridLine = {
  cells: [
    { syllable: 'or', word_index: 0 },
    { syllable: 'ange', word_index: 0 },
    { rest: true },
    { syllable: 'door', word_index: 1 },
    { rest: true },
    { syllable: 'hinge', word_index: 2 },
  ],
  syllable_count: 4,
  raw_text: 'orange _ door_hinge',
};
const snippet = { id: 'a', raw_text: 'orange _ door_hinge', grid_data: { lines: [restLine] } } as unknown as Snippet;

describe('anchor words skip rest marks', () => {
  it('counts word tokens only', () => {
    expect(lineWords(restLine)).toEqual(['orange', 'door', 'hinge']);
    expect(lineWordCount(restLine)).toBe(3);
  });

  it('range text and fit address words, not marks', () => {
    expect(rangeText(snippet, { lineIndex: 0, wordStart: 1, wordEnd: 3 })).toBe('door hinge');
    expect(rangeFits(snippet, { lineIndex: 0, wordStart: 2, wordEnd: 3 })).toBe(true);
    expect(rangeFits(snippet, { lineIndex: 0, wordStart: 2, wordEnd: 4 })).toBe(false);
  });

  it('a sliced line has display text without _', () => {
    expect(sliceGridLine(restLine, { lineIndex: 0, wordStart: 1, wordEnd: 3 }).raw_text).toBe('door hinge');
  });
});
