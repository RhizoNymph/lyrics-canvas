import { describe, it, expect } from 'vitest';
import { countSyllables, fitToMode, linesOf, textLines, unionTagIds, wordCount } from './grid';
import { line, snippet, tag } from './fixtures';

describe('textLines / wordCount', () => {
  it('splits like the backend syllabifier: trimmed, blanks dropped', () => {
    expect(textLines('  one two \n\n three ')).toEqual(['one two', 'three']);
    expect(wordCount('  one two \n\n three ')).toBe(3);
    expect(wordCount('')).toBe(0);
  });
});

describe('linesOf', () => {
  it('returns the grid lines when the snippet has a grid', () => {
    const s = snippet('a', 'one two\nthree');
    expect(linesOf(s)).toBe(s.grid_data!.lines);
  });

  it('falls back to cell-less lines from the text when the grid is null', () => {
    const s = snippet('a', 'one two\n\nthree', { grid_data: null });
    expect(linesOf(s)).toEqual([
      { cells: [], syllable_count: 0, raw_text: 'one two' },
      { cells: [], syllable_count: 0, raw_text: 'three' },
    ]);
  });
});

describe('countSyllables', () => {
  it('ignores rests', () => {
    expect(countSyllables(line('a b', 'a . b .').cells)).toBe(2);
  });
});

describe('fitToMode', () => {
  it('auto leaves ragged lines alone', () => {
    const lines = [line('a b'), line('c')];
    expect(fitToMode(lines, 'auto', null)).toEqual({ lines, beats_per_line: null });
  });

  it('fixed pads short lines with rests to beats_per_line', () => {
    const { lines, beats_per_line } = fitToMode([line('a b'), line('c')], 'fixed', 4);
    expect(beats_per_line).toBe(4);
    expect(lines.map((l) => l.cells.length)).toEqual([4, 4]);
    expect(lines[1].cells.slice(1)).toEqual([{ rest: true }, { rest: true }, { rest: true }]);
    expect(lines[1].syllable_count).toBe(1);
  });

  it('fixed raises beats_per_line to the longest line instead of truncating', () => {
    const { lines, beats_per_line } = fitToMode([line('a b c d e'), line('f')], 'fixed', 4);
    expect(beats_per_line).toBe(5);
    expect(lines.map((l) => l.cells.length)).toEqual([5, 5]);
    expect(lines[0].cells.map((c) => c.syllable)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('fixed with no beats_per_line uses the longest line', () => {
    const { lines, beats_per_line } = fitToMode([line('a b'), line('c')], 'fixed', null);
    expect(beats_per_line).toBe(2);
    expect(lines[1].cells).toEqual([{ syllable: 'c', stress: 1, word_index: 0 }, { rest: true }]);
  });
});

describe('unionTagIds', () => {
  it('unions in first-seen order without duplicates', () => {
    const a = snippet('a', 'a', { tags: [tag('t2'), tag('t1')] });
    const b = snippet('b', 'b', { tags: [tag('t1'), tag('t3')] });
    expect(unionTagIds([a, b])).toEqual(['t2', 't1', 't3']);
  });
});
