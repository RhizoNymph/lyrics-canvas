import { describe, it, expect } from 'vitest';
import { listRows, shownRange, canLoadMore } from './candidateList';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate } from '@/types/rhyme';

const cand = (id: string): RhymeCandidate => ({ id, text: id } as unknown as RhymeCandidate);
const many = (n: number) => Array.from({ length: n }, (_, i) => cand(`c${i}`));

describe('listRows', () => {
  it('returns the loaded candidates in order', () => {
    expect(listRows([cand('x'), cand('y')], 0, new Set()).map((c) => c.id)).toEqual(['x', 'y']);
  });

  it('excludes dismissed ids', () => {
    const rows = listRows([cand('x'), cand('y'), cand('z')], 0, new Set(['y']));
    expect(rows.map((c) => c.id)).toEqual(['x', 'z']);
  });

  it('limits the window to the pages loaded so far (offset + page size)', () => {
    expect(listRows(many(100), 0, new Set()).length).toBe(RHYME_PAGE_SIZE);
    expect(listRows(many(100), RHYME_PAGE_SIZE, new Set()).length).toBe(2 * RHYME_PAGE_SIZE);
    expect(listRows(many(5), RHYME_PAGE_SIZE, new Set()).length).toBe(5);
  });

  it('applies the dismissed filter before the window so a dismissed row frees a slot', () => {
    const rows = listRows(many(RHYME_PAGE_SIZE + 1), 0, new Set(['c0']));
    expect(rows.length).toBe(RHYME_PAGE_SIZE);
    expect(rows[rows.length - 1].id).toBe(`c${RHYME_PAGE_SIZE}`);
  });
});

describe('shownRange', () => {
  it('is 0–0 with no rows', () => {
    expect(shownRange(0, 0)).toEqual({ start: 0, end: 0 });
    expect(shownRange(0, 12)).toEqual({ start: 0, end: 0 });
  });

  it('counts from 1 to the number of rows', () => {
    expect(shownRange(5, 77)).toEqual({ start: 1, end: 5 });
  });
});

describe('canLoadMore', () => {
  it('is true only while idle and another page exists', () => {
    expect(canLoadMore(false, 0, 41)).toBe(true);
    expect(canLoadMore(false, 0, 40)).toBe(false);
    expect(canLoadMore(true, 0, 41)).toBe(false);
    expect(canLoadMore(false, 40, 80)).toBe(false);
    expect(canLoadMore(false, 40, 81)).toBe(true);
  });
});
