import { describe, it, expect } from 'vitest';
import { highlightSegments } from './highlight';
import { parseSearchQuery } from './match';

const segs = (text: string, q: string) => highlightSegments(text, parseSearchQuery(q));

describe('highlightSegments', () => {
  it('marks every occurrence of every term, case-insensitively', () => {
    expect(segs('Black cat, black night', 'black')).toEqual([
      { text: 'Black', match: true },
      { text: ' cat, ', match: false },
      { text: 'black', match: true },
      { text: ' night', match: false },
    ]);
  });

  it('merges overlapping terms into one run', () => {
    expect(segs('abcdef', 'bcd cde')).toEqual([
      { text: 'a', match: false },
      { text: 'bcde', match: true },
      { text: 'f', match: false },
    ]);
  });

  it('is one plain segment without terms or matches', () => {
    expect(segs('hello', '')).toEqual([{ text: 'hello', match: false }]);
    expect(segs('hello', 'zzz')).toEqual([{ text: 'hello', match: false }]);
    expect(segs('', 'a')).toEqual([]);
  });

  it('falls back to plain text when lowercasing changes the length', () => {
    expect(segs('İstanbul', 'stan')).toEqual([{ text: 'İstanbul', match: false }]);
  });
});
