import type { GridCell, GridLine } from '@/types';
import { fitToMode, linesOf, unionTagIds, withCount, wordCount } from './grid';
import type { MergedSnippet, NonEmptySnippets } from './types';

/**
 * Join snippets left-to-right line by line: result line i is line i of each snippet in
 * order, cells concatenated and `word_index` re-based past the words already on the line,
 * text joined with a single space. A snippet with no line i contributes nothing to it, so
 * later contributors simply follow the previous ones. The first snippet supplies the type,
 * grid mode, position and group; `fixed` mode pads to the longest line.
 */
export function mergeHorizontal(snippets: NonEmptySnippets): MergedSnippet {
  const [first] = snippets;
  const perSnippet = snippets.map(linesOf);
  const lineCount = perSnippet.reduce((n, lines) => Math.max(n, lines.length), 0);
  const joined: GridLine[] = [];
  for (let i = 0; i < lineCount; i++) {
    const cells: GridCell[] = [];
    const parts: string[] = [];
    for (const lines of perSnippet) {
      const line = lines[i];
      if (!line) continue;
      const offset = wordCount(parts.join(' '));
      for (const cell of line.cells) {
        cells.push(typeof cell.word_index === 'number' ? { ...cell, word_index: cell.word_index + offset } : { ...cell });
      }
      if (line.raw_text.trim()) parts.push(line.raw_text.trim());
    }
    joined.push(withCount({ cells, raw_text: parts.join(' ') }));
  }
  const { lines, beats_per_line } = fitToMode(joined, first.grid_mode, first.beats_per_line);
  return {
    raw_text: lines.map((l) => l.raw_text).join('\n'),
    grid_data: { lines },
    snippet_type: first.snippet_type,
    grid_mode: first.grid_mode,
    beats_per_line,
    group_id: first.group_id,
    position_x: first.position_x,
    position_y: first.position_y,
    tag_ids: unionTagIds(snippets),
    source_ids: snippets.map((s) => s.id),
  };
}
