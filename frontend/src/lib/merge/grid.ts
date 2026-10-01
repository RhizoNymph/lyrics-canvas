import type { GridCell, GridLine, GridMode, Snippet } from '@/types';

const REST: GridCell = { rest: true };

/** Split text the way the backend syllabifier does: trimmed, blank lines dropped. */
export function textLines(rawText: string): string[] {
  return rawText
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
}

export function wordCount(text: string): number {
  return textLines(text).reduce((n, line) => n + line.split(/\s+/).length, 0);
}

/**
 * The lines of a snippet's beat grid. A snippet without a grid contributes its text lines
 * with no cells, so merging stays best effort rather than failing.
 */
export function linesOf(snippet: Snippet): GridLine[] {
  if (snippet.grid_data) return snippet.grid_data.lines;
  return textLines(snippet.raw_text).map((raw_text) => ({ cells: [], syllable_count: 0, raw_text }));
}

export function countSyllables(cells: readonly GridCell[]): number {
  return cells.filter((c) => !c.rest).length;
}

export function withCount(line: Omit<GridLine, 'syllable_count'>): GridLine {
  return { ...line, syllable_count: countSyllables(line.cells) };
}

/**
 * Fit lines to a grid mode. In `fixed` mode every line is padded with rests to
 * `beats_per_line`, which is raised to the longest line rather than truncating anything.
 * In `auto` mode lines are returned unchanged.
 */
export function fitToMode(
  lines: GridLine[],
  grid_mode: GridMode,
  beats_per_line: number | null
): { lines: GridLine[]; beats_per_line: number | null } {
  if (grid_mode !== 'fixed') return { lines, beats_per_line };
  const longest = lines.reduce((n, l) => Math.max(n, l.cells.length), 0);
  const beats = Math.max(beats_per_line ?? 0, longest);
  if (beats === 0) return { lines, beats_per_line: beats_per_line };
  const padded = lines.map((l) =>
    l.cells.length >= beats ? l : { ...l, cells: [...l.cells, ...Array.from({ length: beats - l.cells.length }, () => ({ ...REST }))] }
  );
  return { lines: padded, beats_per_line: beats };
}

/** Union of tag ids across snippets, in first-seen order. */
export function unionTagIds(snippets: readonly Snippet[]): string[] {
  const seen = new Set<string>();
  for (const s of snippets) for (const t of s.tags) seen.add(t.id);
  return [...seen];
}
