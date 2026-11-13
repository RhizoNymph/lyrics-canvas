/**
 * Picking the pattern's line range (contract §2.1): two clicks on line numbers in the anchor chip's
 * expansion, or the "Pattern (last N lines)" shortcut. A range is `[lineStart, lineEnd)` of one snippet with
 * at most 16 lines and at least 2 lines with words (rests do not count as words).
 */
import type { GridLine } from '@/types';
import { PATTERN_MAX_LINES, PATTERN_MIN_LINES } from '@/types/rhymePattern';
import { lineWordCount } from '@/lib/rhyme/anchorRange';
import type { LineRange } from './types';

export type LinePick = { readonly kind: 'none' } | { readonly kind: 'first'; readonly line: number };

export const NO_LINE_PICK: LinePick = Object.freeze({ kind: 'none' });

/** First click remembers the line; the same line again cancels; another line closes the range. */
export function clickLineNumber(pick: LinePick, line: number): { pick: LinePick; range: LineRange | null } {
  if (pick.kind === 'none') return { pick: { kind: 'first', line }, range: null };
  if (pick.line === line) return { pick: NO_LINE_PICK, range: null };
  return { pick: NO_LINE_PICK, range: { lineStart: Math.min(pick.line, line), lineEnd: Math.max(pick.line, line) + 1 } };
}

export type PatternRangeCheck =
  | { readonly ok: true; readonly range: LineRange }
  | { readonly ok: false; readonly reason: 'out_of_range' | 'too_few_lines' | 'too_many_lines' };

const hasWords = (line: GridLine) => lineWordCount(line) > 0;

export function checkPatternRange(lines: readonly GridLine[], range: LineRange): PatternRangeCheck {
  const { lineStart, lineEnd } = range;
  if (!Number.isInteger(lineStart) || !Number.isInteger(lineEnd) || lineStart < 0 || lineEnd > lines.length || lineEnd <= lineStart) {
    return { ok: false, reason: 'out_of_range' };
  }
  if (lineEnd - lineStart > PATTERN_MAX_LINES) return { ok: false, reason: 'too_many_lines' };
  if (lines.slice(lineStart, lineEnd).filter(hasWords).length < PATTERN_MIN_LINES) return { ok: false, reason: 'too_few_lines' };
  return { ok: true, range };
}

export function describeRangeProblem(reason: 'out_of_range' | 'too_few_lines' | 'too_many_lines'): string {
  switch (reason) {
    case 'out_of_range':
      return 'Those lines are not in the snippet.';
    case 'too_few_lines':
      return 'Pick at least 2 lines with words.';
    case 'too_many_lines':
      return `At most ${PATTERN_MAX_LINES} lines.`;
  }
}

export function wordyLineCount(lines: readonly GridLine[]): number {
  return lines.filter(hasWords).length;
}

/** The shortcut's default N: min(4, lines with words); null when there are fewer than 2. */
export function defaultLastN(lines: readonly GridLine[]): number | null {
  const n = wordyLineCount(lines);
  return n < PATTERN_MIN_LINES ? null : Math.min(4, n);
}

/** The stepper's upper bound: min(16, lines with words). */
export function lastNMax(lines: readonly GridLine[]): number {
  return Math.min(PATTERN_MAX_LINES, wordyLineCount(lines));
}

/**
 * The range from the Nth-last line with words through the last line with words, cut short when it would
 * exceed 16 lines; null when it would hold fewer than 2 lines with words.
 */
export function lastLinesRange(lines: readonly GridLine[], n: number): LineRange | null {
  let end = lines.length;
  while (end > 0 && !hasWords(lines[end - 1])) end--;
  if (end === 0) return null;
  let start = end;
  let found = 0;
  for (let i = end - 1; i >= 0 && found < n; i--) {
    if (end - i > PATTERN_MAX_LINES) break;
    if (hasWords(lines[i])) {
      found++;
      start = i;
    }
  }
  return found < PATTERN_MIN_LINES ? null : { lineStart: start, lineEnd: end };
}
