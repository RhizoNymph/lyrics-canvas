import type { GridCell, GridLine } from '@/types';
import { tokenizeLine } from './restMarks';

/** Count vowel groups in a word as a rough syllable estimate (silent-e adjusted). */
export function estimateSyllableCount(word: string): number {
  const clean = word.toLowerCase().replace(/[^a-z]/g, '');
  if (!clean) return 1;
  const groups = clean.match(/[aeiouy]+/g);
  if (!groups) return 1;
  let count = groups.length;
  if (clean.length > 2 && clean.endsWith('e') && count > 1) count--;
  return Math.max(count, 1);
}

/** Split a word into roughly `estimateSyllableCount(word)` character chunks for a live expanding grid. */
export function splitWord(word: string): string[] {
  const n = estimateSyllableCount(word);
  if (n <= 1) return [word];
  const partLen = Math.ceil(word.length / n);
  const parts: string[] = [];
  for (let i = 0; i < n; i++) {
    const start = i * partLen;
    const end = i === n - 1 ? word.length : (i + 1) * partLen;
    parts.push(word.slice(start, end));
  }
  return parts;
}

/**
 * Instant local syllabification of one line; the API result replaces it once it arrives. Each `_`
 * becomes a bare rest cell (rest marks, see restMarks.ts); words are split by the vowel-group estimate.
 */
export function localSyllabifyLine(text: string): GridLine {
  const raw_text = text.trim();
  if (!raw_text) return { cells: [], syllable_count: 0, raw_text: '' };
  const cells: GridCell[] = [];
  let syllables = 0;
  for (const token of tokenizeLine(raw_text)) {
    if (token.kind === 'rest') {
      cells.push({ rest: true });
      continue;
    }
    for (const syllable of splitWord(token.text)) {
      cells.push({ syllable, word_index: token.wordIndex, word: token.text });
      syllables++;
    }
  }
  return { cells, syllable_count: syllables, raw_text };
}
