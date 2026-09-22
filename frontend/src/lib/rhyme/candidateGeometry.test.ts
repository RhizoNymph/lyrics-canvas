import { describe, it, expect } from 'vitest';
import { candidateCardSize, candidateTopLeft, CANDIDATE_MIN_WIDTH } from './candidateGeometry';
import { CELL_WIDTH, CELL_HEIGHT, PADDING, HEADER_HEIGHT, COUNT_COLUMN_WIDTH } from '@/components/canvas/gridConstants';
import type { RhymeCandidate } from '@/types/rhyme';

const cand = (cells: number): RhymeCandidate =>
  ({ grid_line: { cells: Array.from({ length: cells }, () => ({ syllable: 'x' })), syllable_count: cells, raw_text: 'x' } } as unknown as RhymeCandidate);

describe('candidateCardSize', () => {
  it('grows with the number of cells and has one grid row', () => {
    expect(candidateCardSize(cand(3))).toEqual({
      width: PADDING * 2 + 3 * CELL_WIDTH + COUNT_COLUMN_WIDTH,
      height: HEADER_HEIGHT + PADDING * 2 + CELL_HEIGHT,
    });
  });

  it('never goes below the minimum width', () => {
    expect(candidateCardSize(cand(1)).width).toBe(CANDIDATE_MIN_WIDTH);
    expect(candidateCardSize(cand(0)).width).toBe(CANDIDATE_MIN_WIDTH);
  });
});

describe('candidateTopLeft', () => {
  it('centres the card on its layout point', () => {
    const size = candidateCardSize(cand(3));
    const tl = candidateTopLeft({ x: 100, y: 50 }, size);
    expect(tl).toEqual({ x: 100 - size.width / 2, y: 50 - size.height / 2 });
  });
});
