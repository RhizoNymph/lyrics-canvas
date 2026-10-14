/**
 * What a beat-grid cell shows and how it is toned, shared by the Pixi card (gridDraw.ts) and the DOM
 * list preview (GridPreview.tsx). Pure, no Pixi imports.
 */
import type { GridCell } from '@/types';

export type CellTextKind = 'rest' | 'stressed' | 'normal';

export function cellTextKind(cell: GridCell): CellTextKind {
  if (cell.rest) return 'rest';
  return cell.stress !== undefined && cell.stress > 0 ? 'stressed' : 'normal';
}

export function cellLabel(cell: GridCell): string {
  return cell.rest ? '·' : cell.syllable || '';
}

/** Text colours per kind, plus the syllable-count colour and the even-row band. */
export const GRID_TEXT_COLORS: Record<CellTextKind | 'count', string> = {
  normal: '#d0d0e8',
  stressed: '#ffffff',
  rest: '#555577',
  count: '#7a7a9a',
};
export const GRID_ROW_BAND = 'rgba(36, 36, 72, 0.5)';
export const GRID_LINE_COLOR = 'rgba(74, 74, 106, 0.5)';
