import type { GridLine } from '@/types';
import { countSyllables, rebaseWordIndexes } from './wordIndex';

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(Math.max(n, lo), hi);
}

/**
 * Move `cells[start:end)` so the chunk begins at index `to`, where `to` is an
 * index into the line with the chunk removed (clamped into range). Rests are
 * ordinary cells, so dragging over them shifts timing and dragging past other
 * syllables reorders words. Word indexes are re-based by first appearance and
 * `syllable_count` recomputed. `raw_text` is left for `withRebuiltText`.
 * Returns the same line for an empty or out-of-range chunk.
 */
export function moveChunk(line: GridLine, start: number, end: number, to: number): GridLine {
  const n = line.cells.length;
  if (start < 0 || end > n || end <= start) return line;
  const chunk = line.cells.slice(start, end);
  const rest = [...line.cells.slice(0, start), ...line.cells.slice(end)];
  const target = clamp(to, 0, rest.length);
  const cells = rebaseWordIndexes([...rest.slice(0, target), ...chunk, ...rest.slice(target)]);
  return { ...line, cells, syllable_count: countSyllables(cells) };
}
