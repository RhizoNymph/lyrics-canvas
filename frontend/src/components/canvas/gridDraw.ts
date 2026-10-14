/**
 * Beat-grid drawing for Pixi cards (SnippetCard): row backgrounds, grid lines, per-cell text
 * placement and the text styles. Geometry constants come from gridConstants.ts.
 */
import { TextStyle } from 'pixi.js';
import type { Graphics as PixiGraphics } from 'pixi.js';
import type { GridCell, GridLine } from '@/types';
import { CELL_WIDTH, CELL_HEIGHT } from './gridConstants';
import { cellLabel, cellTextKind, GRID_TEXT_COLORS } from './gridCellText';
import type { CellTextKind } from './gridCellText';

export { CELL_WIDTH, CELL_HEIGHT, PADDING, HEADER_HEIGHT } from './gridConstants';

export interface GridFrame {
  /** Combined scale (canvas zoom * card scale). */
  s: number;
  /** Screen-space left edge of the grid inside the card. */
  left: number;
  /** Screen-space top edge of the grid inside the card. */
  top: number;
  /** Grid never extends past this screen-space x. */
  maxRight: number;
}

/** Alternating row backgrounds plus horizontal and vertical lines. No-op for empty input. */
export function drawBeatGrid(g: PixiGraphics, lines: ReadonlyArray<GridLine>, frame: GridFrame): void {
  if (lines.length === 0) return;
  const { s, left, top, maxRight } = frame;
  const cellW = CELL_WIDTH * s;
  const cellH = CELL_HEIGHT * s;
  const maxCells = Math.max(...lines.map((l) => l.cells.length), 1);
  const right = Math.min(left + maxCells * cellW, maxRight);
  const bottom = top + lines.length * cellH;

  for (let row = 0; row < lines.length; row++) {
    if (row % 2 === 0) {
      g.beginFill(0x242448, 0.5);
      g.drawRect(left, top + row * cellH, right - left, cellH);
      g.endFill();
    }
  }

  g.lineStyle(1, 0x4a4a6a, 0.5);
  for (let row = 0; row <= lines.length; row++) {
    const y = top + row * cellH;
    g.moveTo(left, y);
    g.lineTo(right, y);
  }
  for (let col = 0; col <= maxCells; col++) {
    const x = left + col * cellW;
    if (x > right + 1) break;
    g.moveTo(x, top);
    g.lineTo(x, bottom);
  }
}

export type { CellTextKind };

export interface CellTextPlacement {
  text: string;
  x: number;
  y: number;
  kind: CellTextKind;
}

/** Where a cell's text goes and which style it takes. */
export function placeCellText(cell: GridCell, row: number, col: number, frame: Pick<GridFrame, 's' | 'left' | 'top'>): CellTextPlacement {
  const { s, left, top } = frame;
  const cellW = CELL_WIDTH * s;
  const cellH = CELL_HEIGHT * s;
  const x = left + col * cellW + 4 * s;
  const y = top + row * cellH + 3 * s;
  const kind = cellTextKind(cell);
  return { text: cellLabel(cell), x: kind === 'rest' ? x + cellW * 0.35 : x, y, kind };
}

export interface GridTextStyles {
  cell: TextStyle;
  stressed: TextStyle;
  rest: TextStyle;
  count: TextStyle;
}

export function createGridTextStyles(s: number): GridTextStyles {
  return {
    cell: new TextStyle({ fontSize: 13 * s, fill: GRID_TEXT_COLORS.normal, fontFamily: 'monospace' }),
    stressed: new TextStyle({ fontSize: 13 * s, fill: GRID_TEXT_COLORS.stressed, fontWeight: 'bold', fontFamily: 'monospace' }),
    rest: new TextStyle({ fontSize: 13 * s, fill: GRID_TEXT_COLORS.rest, fontFamily: 'monospace' }),
    count: new TextStyle({ fontSize: 10 * s, fill: GRID_TEXT_COLORS.count, fontFamily: 'monospace' }),
  };
}

export function styleForKind(styles: GridTextStyles, kind: CellTextKind): TextStyle {
  switch (kind) {
    case 'rest':
      return styles.rest;
    case 'stressed':
      return styles.stressed;
    case 'normal':
      return styles.cell;
  }
}
