/**
 * Partial-line anchors: a word range of one snippet line, picked by clicking a start word and
 * an end word on the chip's syllable strip. Ranges snap to word boundaries (the engine rhymes
 * on words) and are stored with an exclusive end, mirroring the API's `word_start` /
 * `word_end`.
 */
import type { GridLine, Snippet } from '@/types';
import type { AnchorRange, SnippetAnchorRef } from '@/types/rhyme';

/** The chip's click state: the range so far, and whether the next click sets its end. */
export interface RangeClickState {
  range: AnchorRange | null;
  /** True after the first click: the next click on the same line closes the range. */
  awaitingEnd: boolean;
}

export const NO_RANGE: RangeClickState = Object.freeze({ range: null, awaitingEnd: false });

/** Two inclusive word clicks on one line, in any order, as a stored (exclusive-end) range. */
export function wordSpan(lineIndex: number, a: number, b: number): AnchorRange {
  return { lineIndex, wordStart: Math.min(a, b), wordEnd: Math.max(a, b) + 1 };
}

/**
 * Click-to-range transitions:
 * - no range, or a click on another line: start a single-word range there and await the end;
 * - awaiting the end: the start word again clears; another word closes the range;
 * - complete range: the start word again clears; another word starts over from it.
 */
export function clickWord(state: RangeClickState, lineIndex: number, wordIndex: number): RangeClickState {
  const { range, awaitingEnd } = state;
  if (range === null || range.lineIndex !== lineIndex) {
    return { range: wordSpan(lineIndex, wordIndex, wordIndex), awaitingEnd: true };
  }
  if (wordIndex === range.wordStart) return NO_RANGE;
  if (awaitingEnd) return { range: wordSpan(lineIndex, range.wordStart, wordIndex), awaitingEnd: false };
  return { range: wordSpan(lineIndex, wordIndex, wordIndex), awaitingEnd: true };
}

export function cellInRange(cell: GridLine['cells'][number], range: AnchorRange): boolean {
  return cell.rest !== true && cell.word_index !== undefined && cell.word_index >= range.wordStart && cell.word_index < range.wordEnd;
}

/**
 * The cells of `line` whose word index falls in the range, re-based to it, with leading and
 * trailing rests dropped and interior rests kept (the server slices the echoed grid line the
 * same way).
 */
export function sliceGridLine(line: GridLine, range: AnchorRange): GridLine {
  const cells: GridLine['cells'] = [];
  for (const cell of line.cells) {
    if (cell.rest) {
      if (cells.length > 0) cells.push(cell);
      continue;
    }
    if (cellInRange(cell, range)) cells.push({ ...cell, word_index: (cell.word_index as number) - range.wordStart });
  }
  while (cells.length > 0 && cells[cells.length - 1].rest) cells.pop();
  return {
    cells,
    syllable_count: cells.filter((c) => !c.rest).length,
    raw_text: lineWords(line).slice(range.wordStart, range.wordEnd).join(' '),
  };
}

/** The line's words as the server tokenises them (whitespace split of the stripped line). */
export function lineWords(line: GridLine): string[] {
  const text = line.raw_text.trim();
  return text.length === 0 ? [] : text.split(/\s+/);
}

export function lineWordCount(line: GridLine): number {
  return lineWords(line).length;
}

export function gridLineOf(snippet: Snippet, lineIndex: number): GridLine | null {
  return snippet.grid_data?.lines[lineIndex] ?? null;
}

/** The selected words of the snippet line, or null when the line does not exist. */
export function rangeText(snippet: Snippet, range: AnchorRange): string | null {
  const line = gridLineOf(snippet, range.lineIndex);
  if (line === null) return null;
  return lineWords(line).slice(range.wordStart, range.wordEnd).join(' ');
}

/** True when the range addresses existing words of an existing line (what the server will accept). */
export function rangeFits(snippet: Snippet, range: AnchorRange): boolean {
  const line = gridLineOf(snippet, range.lineIndex);
  if (line === null) return false;
  return range.wordStart >= 0 && range.wordStart < range.wordEnd && range.wordEnd <= lineWordCount(line);
}

/** The request anchor for a selected snippet: its whole last line, or the given word range. */
export function anchorRefFor(snippet: Snippet, range: AnchorRange | undefined): SnippetAnchorRef {
  if (range === undefined) return { kind: 'snippet', snippet_id: snippet.id, line_index: null };
  return { kind: 'snippet', snippet_id: snippet.id, line_index: range.lineIndex, word_start: range.wordStart, word_end: range.wordEnd };
}
