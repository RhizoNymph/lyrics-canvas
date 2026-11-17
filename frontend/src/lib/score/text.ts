/** Keep a snippet's raw_text in step with rest-linked grid lines (spec/score_format.md section 13). */
import { ScoreTextMismatch } from './errors';
import { gridLines } from './gridView';
import type { GridJson } from './gridView';

/** Grid line index -> new `raw_text` for every line whose `raw_text` changed (grids of equal length). */
export function changedLineTexts(oldGrid: GridJson, newGrid: GridJson): Map<number, string> {
  const oldLines = gridLines(oldGrid);
  const newLines = gridLines(newGrid);
  if (oldLines.length !== newLines.length) throw new RangeError('changedLineTexts needs grids with the same number of lines');
  const out = new Map<number, string>();
  newLines.forEach((line, i) => {
    if (oldLines[i].raw_text !== line.raw_text) out.set(i, typeof line.raw_text === 'string' ? line.raw_text : '');
  });
  return out;
}

/**
 * Replace the text lines that are grid lines `replacements` (the n-th non-blank line is grid line n);
 * blank lines and other lines are kept byte for byte. Throws `ScoreTextMismatch`.
 */
export function spliceLineTexts(rawText: string, replacements: ReadonlyMap<number, string>): string {
  const pieces = rawText.split('\n');
  let gridIndex = 0;
  pieces.forEach((piece, position) => {
    if (piece.trim() === '') return;
    const replacement = replacements.get(gridIndex);
    if (replacement !== undefined) pieces[position] = replacement;
    gridIndex++;
  });
  const missing = [...replacements.keys()].filter((index) => index >= gridIndex || index < 0);
  if (missing.length > 0) throw new ScoreTextMismatch(Math.min(...missing), gridIndex);
  return pieces.join('\n');
}
