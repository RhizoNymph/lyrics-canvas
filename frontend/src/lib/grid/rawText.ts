import type { GridCell, GridData, GridLine } from '@/types';
import { REST_MARK } from './restMarks';

/** A cell's word key: its `word_index`, or a unique negative key for an index-less (anonymous) cell. */
function wordKeys(cells: readonly GridCell[]): (number | null)[] {
  let anonymous = -1;
  return cells.map((cell) => (cell.rest ? null : typeof cell.word_index === 'number' ? cell.word_index : anonymous--));
}

/**
 * The line's canonical text as implied by its cells (rest marks contract §1): walking the cells, a rest
 * emits `_`; the first syllable of each word emits the whole word, spelled by its surface `word` when
 * known, else by all its syllables concatenated; later syllables of that word emit nothing. Pieces are
 * joined by single spaces; words without any text are dropped. Cells without a word_index are their own
 * words.
 */
export function rebuildRawText(line: GridLine): string {
  const keys = wordKeys(line.cells);
  const spelled = new Map<number, string>();
  const surface = new Map<number, string>();
  line.cells.forEach((cell, i) => {
    const key = keys[i];
    if (key === null) return;
    spelled.set(key, (spelled.get(key) ?? '') + (cell.syllable ?? ''));
    if (!surface.has(key) && cell.word !== undefined) surface.set(key, cell.word);
  });
  const pieces: string[] = [];
  const emitted = new Set<number>();
  keys.forEach((key) => {
    if (key === null) {
      pieces.push(REST_MARK);
      return;
    }
    if (emitted.has(key)) return;
    emitted.add(key);
    const word = surface.get(key) ?? spelled.get(key) ?? '';
    if (word.length > 0) pieces.push(word);
  });
  return pieces.join(' ');
}

/** The line with `raw_text` replaced by `rebuildRawText(line)`. */
export function withRebuiltText(line: GridLine): GridLine {
  const raw_text = rebuildRawText(line);
  return raw_text === line.raw_text ? line : { ...line, raw_text };
}

export function rebuildRawTextAll(grid: GridData): string {
  return grid.lines.map(rebuildRawText).join('\n');
}
