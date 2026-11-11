import { describe, it, expect } from 'vitest';
import { EMPTY_SEARCH, isSearchActive, matchesSearch, parseSearchQuery } from './match';

describe('parseSearchQuery', () => {
  it('NFC-normalises, trims, splits on whitespace and lowercases', () => {
    expect(parseSearchQuery('  Café   NIGHT\t').terms).toEqual(['café', 'night']);
  });

  it('is inactive for blank input', () => {
    expect(isSearchActive(parseSearchQuery(' \n '))).toBe(false);
    expect(isSearchActive(EMPTY_SEARCH)).toBe(false);
    expect(isSearchActive(parseSearchQuery('x'))).toBe(true);
  });
});

describe('matchesSearch', () => {
  it('needs every term as a substring of the display text, case-insensitively', () => {
    const q = parseSearchQuery('NIGH bla');
    expect(matchesSearch(q, 'Black cat\nwhite night')).toBe(true);
    expect(matchesSearch(q, 'white night')).toBe(false);
  });

  it('ignores rest marks in the text, so a term never spans a mark and _ never matches', () => {
    expect(matchesSearch(parseSearchQuery('go now'), 'go_now')).toBe(true);
    expect(matchesSearch(parseSearchQuery('gonow'), 'go_now')).toBe(false);
    expect(matchesSearch(parseSearchQuery('o_n'), 'go_now')).toBe(false);
  });

  it('treats regex and LIKE characters literally', () => {
    expect(matchesSearch(parseSearchQuery('a.c'), 'abc')).toBe(false);
    expect(matchesSearch(parseSearchQuery('a.c'), 'xa.cx')).toBe(true);
  });

  it('matches a decomposed text against a composed query', () => {
    expect(matchesSearch(parseSearchQuery('café'), 'CAFÉ au lait')).toBe(true);
  });

  it('matches anything when empty', () => {
    expect(matchesSearch(EMPTY_SEARCH, '')).toBe(true);
  });
});
