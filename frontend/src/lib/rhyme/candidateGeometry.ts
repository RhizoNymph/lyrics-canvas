/** Pure size/position helpers for candidate cards (no Pixi imports so the store can use them). */
import { CELL_WIDTH, CELL_HEIGHT, PADDING, HEADER_HEIGHT, COUNT_COLUMN_WIDTH } from '@/components/canvas/gridConstants';
import type { RhymeCandidate } from '@/types/rhyme';
import type { Point } from './radialLayout';

export interface Size {
  width: number;
  height: number;
}

export const CANDIDATE_MIN_WIDTH = 150;

/** Unscaled (world-unit) card size: one grid row wide enough for every cell plus the count column. */
export function candidateCardSize(c: Pick<RhymeCandidate, 'grid_line'>): Size {
  const cells = c.grid_line.cells.length;
  return {
    width: Math.max(CANDIDATE_MIN_WIDTH, PADDING * 2 + cells * CELL_WIDTH + COUNT_COLUMN_WIDTH),
    height: HEADER_HEIGHT + PADDING * 2 + CELL_HEIGHT,
  };
}

/** Layout positions are card centres; this gives the world top-left used for drawing and promotion. */
export function candidateTopLeft(center: Point, size: Size): Point {
  return { x: center.x - size.width / 2, y: center.y - size.height / 2 };
}
