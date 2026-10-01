import type { GridData, GridLine } from '@/types';

interface WordParts {
  word: string | undefined;
  syllables: string[];
}

/**
 * The line's text as implied by its cells: words in order of first appearance
 * of each `word_index`, each spelled by its surface `word` when known, else by
 * its syllables concatenated. Rests are skipped; cells without a word_index
 * count as their own words.
 */
export function rebuildRawText(line: GridLine): string {
  const parts = new Map<number, WordParts>();
  let anonymous = -1;
  for (const cell of line.cells) {
    if (cell.rest) continue;
    const key = typeof cell.word_index === 'number' ? cell.word_index : anonymous--;
    let entry = parts.get(key);
    if (!entry) {
      entry = { word: undefined, syllables: [] };
      parts.set(key, entry);
    }
    if (entry.word === undefined && cell.word !== undefined) entry.word = cell.word;
    entry.syllables.push(cell.syllable ?? '');
  }
  const words: string[] = [];
  for (const entry of parts.values()) {
    const word = entry.word ?? entry.syllables.join('');
    if (word.length > 0) words.push(word);
  }
  return words.join(' ');
}

/** The line with `raw_text` replaced by `rebuildRawText(line)`. */
export function withRebuiltText(line: GridLine): GridLine {
  const raw_text = rebuildRawText(line);
  return raw_text === line.raw_text ? line : { ...line, raw_text };
}

export function rebuildRawTextAll(grid: GridData): string {
  return grid.lines.map(rebuildRawText).join('\n');
}
