import type { GridLine } from '@/types';
import { countSyllables, rebaseWordIndexes } from './wordIndex';

/** Insert a rest before cell `at` (clamped; past the end appends). */
export function insertRest(line: GridLine, at: number): GridLine {
  const index = Math.min(Math.max(at, 0), line.cells.length);
  const cells = [...line.cells.slice(0, index), { rest: true }, ...line.cells.slice(index)];
  return { ...line, cells };
}

/**
 * Remove cell `at`. Removing a syllable recounts, renumbers words and drops
 * the surface `word` from the rest of that word (it no longer spells it).
 * Same line when out of range.
 */
export function removeCell(line: GridLine, at: number): GridLine {
  if (at < 0 || at >= line.cells.length) return line;
  const removed = line.cells[at];
  const remaining = [...line.cells.slice(0, at), ...line.cells.slice(at + 1)];
  if (removed.rest) return { ...line, cells: remaining };
  const orphaned = remaining.map((cell) =>
    !cell.rest && cell.word !== undefined && cell.word_index === removed.word_index ? { ...cell, word: undefined } : cell,
  );
  const cells = rebaseWordIndexes(orphaned);
  return { ...line, cells, syllable_count: countSyllables(cells) };
}
