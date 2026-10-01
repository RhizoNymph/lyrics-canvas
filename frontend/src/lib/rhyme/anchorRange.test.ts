import { describe, it, expect } from 'vitest';
import {
  NO_RANGE,
  clickWord,
  wordSpan,
  sliceGridLine,
  lineWordCount,
  rangeText,
  rangeFits,
  anchorRefFor,
} from './anchorRange';
import type { GridLine, Snippet } from '@/types';

const line = (raw_text: string, cells: Array<{ w?: number; rest?: boolean; s?: number }>): GridLine => ({
  cells: cells.map((c) => (c.rest ? { rest: true } : { syllable: 'x', stress: c.s ?? 0, word_index: c.w })),
  syllable_count: cells.filter((c) => !c.rest).length,
  raw_text,
});

// "orange door hinge": or-ange (w0 w0) · door (w1) hinge (w2) ·
const hingeLine = line('orange door hinge', [{ w: 0, s: 1 }, { w: 0 }, { rest: true }, { w: 1, s: 1 }, { w: 2, s: 1 }, { rest: true }]);
const snippet = {
  id: 'a',
  raw_text: 'I saw a black cat\norange door hinge',
  grid_data: { lines: [line('I saw a black cat', [{ w: 0 }, { w: 1, s: 1 }, { w: 2 }, { w: 3, s: 1 }, { w: 4, s: 1 }]), hingeLine] },
} as unknown as Snippet;

describe('wordSpan', () => {
  it('orders the clicks and stores the end exclusively', () => {
    expect(wordSpan(1, 2, 4)).toEqual({ lineIndex: 1, wordStart: 2, wordEnd: 5 });
    expect(wordSpan(1, 4, 2)).toEqual({ lineIndex: 1, wordStart: 2, wordEnd: 5 });
    expect(wordSpan(0, 3, 3)).toEqual({ lineIndex: 0, wordStart: 3, wordEnd: 4 });
  });
});

describe('clickWord', () => {
  it('first click starts a single-word range and waits for the end', () => {
    expect(clickWord(NO_RANGE, 1, 1)).toEqual({ range: { lineIndex: 1, wordStart: 1, wordEnd: 2 }, awaitingEnd: true });
  });

  it('second click on the same line closes the range (inclusive click, exclusive end), in either order', () => {
    const started = clickWord(NO_RANGE, 1, 1);
    expect(clickWord(started, 1, 2)).toEqual({ range: { lineIndex: 1, wordStart: 1, wordEnd: 3 }, awaitingEnd: false });
    const startedLate = clickWord(NO_RANGE, 1, 2);
    expect(clickWord(startedLate, 1, 0)).toEqual({ range: { lineIndex: 1, wordStart: 0, wordEnd: 3 }, awaitingEnd: false });
  });

  it('clicking the start word again clears, whether or not the range is complete', () => {
    const started = clickWord(NO_RANGE, 1, 1);
    expect(clickWord(started, 1, 1)).toEqual(NO_RANGE);
    const complete = clickWord(started, 1, 2);
    expect(clickWord(complete, 1, 1)).toEqual(NO_RANGE);
  });

  it('a click on another word after a complete range starts over from that word', () => {
    const complete = clickWord(clickWord(NO_RANGE, 1, 0), 1, 1);
    expect(clickWord(complete, 1, 2)).toEqual({ range: { lineIndex: 1, wordStart: 2, wordEnd: 3 }, awaitingEnd: true });
  });

  it('a click on a different line starts over on that line', () => {
    const started = clickWord(NO_RANGE, 1, 1);
    expect(clickWord(started, 0, 3)).toEqual({ range: { lineIndex: 0, wordStart: 3, wordEnd: 4 }, awaitingEnd: true });
    const complete = clickWord(started, 1, 2);
    expect(clickWord(complete, 0, 4)).toEqual({ range: { lineIndex: 0, wordStart: 4, wordEnd: 5 }, awaitingEnd: true });
  });

  it('never mutates its input', () => {
    const started = clickWord(NO_RANGE, 1, 1);
    const copy = JSON.parse(JSON.stringify(started));
    clickWord(started, 1, 2);
    expect(started).toEqual(copy);
    expect(NO_RANGE).toEqual({ range: null, awaitingEnd: false });
  });
});

describe('sliceGridLine', () => {
  it('keeps the cells whose word index is in range, re-based, dropping leading and trailing rests', () => {
    const out = sliceGridLine(hingeLine, { lineIndex: 1, wordStart: 1, wordEnd: 3 });
    expect(out.cells.map((c) => c.word_index)).toEqual([0, 1]);
    expect(out.syllable_count).toBe(2);
    expect(out.raw_text).toBe('door hinge');
  });

  it('keeps interior rests', () => {
    const out = sliceGridLine(hingeLine, { lineIndex: 1, wordStart: 0, wordEnd: 2 });
    expect(out.cells.map((c) => (c.rest ? 'rest' : c.word_index))).toEqual([0, 0, 'rest', 1]);
    expect(out.syllable_count).toBe(3);
    expect(out.raw_text).toBe('orange door');
  });
});

describe('lineWordCount / rangeText / rangeFits', () => {
  it('counts whitespace-separated words of the line text', () => {
    expect(lineWordCount(hingeLine)).toBe(3);
    expect(lineWordCount(line('  two   words ', []))).toBe(2);
    expect(lineWordCount(line('', []))).toBe(0);
  });

  it('rangeText is the selected words of the snippet line', () => {
    expect(rangeText(snippet, { lineIndex: 0, wordStart: 3, wordEnd: 5 })).toBe('black cat');
    expect(rangeText(snippet, { lineIndex: 1, wordStart: 1, wordEnd: 2 })).toBe('door');
    expect(rangeText(snippet, { lineIndex: 5, wordStart: 0, wordEnd: 1 })).toBeNull();
  });

  it('rangeFits checks the line exists and the range lies inside its words', () => {
    expect(rangeFits(snippet, { lineIndex: 0, wordStart: 0, wordEnd: 5 })).toBe(true);
    expect(rangeFits(snippet, { lineIndex: 1, wordStart: 2, wordEnd: 3 })).toBe(true);
    expect(rangeFits(snippet, { lineIndex: 1, wordStart: 2, wordEnd: 4 })).toBe(false);
    expect(rangeFits(snippet, { lineIndex: 1, wordStart: 2, wordEnd: 2 })).toBe(false);
    expect(rangeFits(snippet, { lineIndex: 2, wordStart: 0, wordEnd: 1 })).toBe(false);
    expect(rangeFits({ id: 'x', raw_text: 'x', grid_data: null } as unknown as Snippet, { lineIndex: 0, wordStart: 0, wordEnd: 1 })).toBe(false);
  });
});

describe('anchorRefFor', () => {
  it('is the whole last line without a range and the word range with one', () => {
    expect(anchorRefFor(snippet, undefined)).toEqual({ kind: 'snippet', snippet_id: 'a', line_index: null });
    expect(anchorRefFor(snippet, { lineIndex: 1, wordStart: 1, wordEnd: 3 })).toEqual({
      kind: 'snippet',
      snippet_id: 'a',
      line_index: 1,
      word_start: 1,
      word_end: 3,
    });
  });
});
