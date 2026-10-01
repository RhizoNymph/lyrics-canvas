import type { GridCell, GridData, GridLine } from '@/types';

/**
 * Renumber `word_index` by order of first appearance (0, 1, 2, ...).
 * Syllables that shared an index keep sharing one, even when another word's
 * cells now sit between them. Rests and index-less cells are returned as-is.
 */
export function rebaseWordIndexes(cells: readonly GridCell[]): GridCell[] {
  const remap = new Map<number, number>();
  return cells.map((cell) => {
    if (cell.rest || typeof cell.word_index !== 'number') return cell;
    let next = remap.get(cell.word_index);
    if (next === undefined) {
      next = remap.size;
      remap.set(cell.word_index, next);
    }
    return cell.word_index === next ? cell : { ...cell, word_index: next };
  });
}

/** Number of sounding (non-rest) cells. */
export function countSyllables(cells: readonly GridCell[]): number {
  let n = 0;
  for (const cell of cells) if (!cell.rest) n++;
  return n;
}

/**
 * Fill `cell.word` from `raw_text.split(/\s+/)[word_index]` where it is missing.
 * Only meaningful while raw_text and word_index agree (a fresh grid, or one
 * rebuilt by `withRebuiltText`).
 */
export function attachWords(line: GridLine): GridLine {
  const text = line.raw_text.trim();
  if (!text) return line;
  const words = text.split(/\s+/);
  let changed = false;
  const cells = line.cells.map((cell) => {
    if (cell.rest || cell.word !== undefined || typeof cell.word_index !== 'number') return cell;
    const word = words[cell.word_index];
    if (word === undefined) return cell;
    changed = true;
    return { ...cell, word };
  });
  return changed ? { ...line, cells } : line;
}

export function attachWordsAll(grid: GridData): GridData {
  return { lines: grid.lines.map(attachWords) };
}
