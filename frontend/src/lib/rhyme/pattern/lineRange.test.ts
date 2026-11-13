import { describe, it, expect } from 'vitest';
import { NO_LINE_PICK, checkPatternRange, clickLineNumber, defaultLastN, lastLinesRange, lastNMax, wordyLineCount } from './lineRange';
import { gridOf } from './fixtures';

const lines = ['one two', '', 'three four', '_ _', 'five six', 'seven'].map(gridOf);

describe('clickLineNumber', () => {
  it('first click remembers the line, a second on another line closes the range in either order', () => {
    const a = clickLineNumber(NO_LINE_PICK, 4);
    expect(a).toEqual({ pick: { kind: 'first', line: 4 }, range: null });
    expect(clickLineNumber(a.pick, 1)).toEqual({ pick: NO_LINE_PICK, range: { lineStart: 1, lineEnd: 5 } });
    expect(clickLineNumber({ kind: 'first', line: 1 }, 3)).toEqual({ pick: NO_LINE_PICK, range: { lineStart: 1, lineEnd: 4 } });
  });

  it('clicking the first line again cancels', () => {
    expect(clickLineNumber({ kind: 'first', line: 2 }, 2)).toEqual({ pick: NO_LINE_PICK, range: null });
  });
});

describe('checkPatternRange', () => {
  it('accepts a range with 2+ lines with words', () => {
    expect(checkPatternRange(lines, { lineStart: 0, lineEnd: 3 })).toEqual({ ok: true, range: { lineStart: 0, lineEnd: 3 } });
  });

  it('refuses fewer than 2 lines with words (blank and rest-only lines do not count)', () => {
    expect(checkPatternRange(lines, { lineStart: 1, lineEnd: 4 })).toEqual({ ok: false, reason: 'too_few_lines' });
  });

  it('refuses ranges outside the snippet or empty', () => {
    expect(checkPatternRange(lines, { lineStart: 4, lineEnd: 7 })).toEqual({ ok: false, reason: 'out_of_range' });
    expect(checkPatternRange(lines, { lineStart: -1, lineEnd: 2 })).toEqual({ ok: false, reason: 'out_of_range' });
    expect(checkPatternRange(lines, { lineStart: 2, lineEnd: 2 })).toEqual({ ok: false, reason: 'out_of_range' });
  });

  it('refuses more than 16 lines', () => {
    const many = Array.from({ length: 20 }, (_, i) => gridOf(`w${i}`));
    expect(checkPatternRange(many, { lineStart: 0, lineEnd: 17 })).toEqual({ ok: false, reason: 'too_many_lines' });
    expect(checkPatternRange(many, { lineStart: 0, lineEnd: 16 }).ok).toBe(true);
  });
});

describe('last N lines', () => {
  it('counts lines with words', () => {
    expect(wordyLineCount(lines)).toBe(4);
  });

  it('defaults N to min(4, lines with words), null under 2', () => {
    expect(defaultLastN(lines)).toBe(4);
    expect(defaultLastN(lines.slice(0, 3))).toBe(2);
    expect(defaultLastN([gridOf('solo')])).toBeNull();
    expect(lastNMax(lines)).toBe(4);
    expect(lastNMax(Array.from({ length: 20 }, (_, i) => gridOf(`w${i}`)))).toBe(16);
  });

  it('spans back from the last line with words to the Nth line with words', () => {
    expect(lastLinesRange(lines, 2)).toEqual({ lineStart: 4, lineEnd: 6 });
    expect(lastLinesRange(lines, 3)).toEqual({ lineStart: 2, lineEnd: 6 });
    expect(lastLinesRange([...lines, gridOf(''), gridOf('_')], 2)).toEqual({ lineStart: 4, lineEnd: 6 });
  });

  it('stops at the snippet start or 16 lines', () => {
    expect(lastLinesRange(lines, 9)).toEqual({ lineStart: 0, lineEnd: 6 });
    const sparse = Array.from({ length: 40 }, (_, i) => gridOf(i % 10 === 0 ? `w${i}` : ''));
    // Lines with words at 0, 10, 20, 30: only 20 and 30 fit in 16 lines.
    expect(lastLinesRange(sparse, 4)).toEqual({ lineStart: 20, lineEnd: 31 });
  });

  it('is null without 2 lines with words', () => {
    expect(lastLinesRange([gridOf('a'), gridOf('')], 2)).toBeNull();
  });
});
