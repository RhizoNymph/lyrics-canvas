import { fitToMode, linesOf, unionTagIds, withCount } from './grid';
import type { MergedSnippet, NonEmptySnippets } from './types';

/**
 * Stack snippets top-to-bottom: the merged grid is every line of every snippet in order,
 * and the text is those lines joined with newlines. The first snippet supplies the type,
 * grid mode, position and group. Differing syllable counts are handled by the grid mode:
 * `fixed` pads short lines with rests (raising beats_per_line to the longest line), `auto`
 * keeps the lines ragged.
 */
export function mergeVertical(snippets: NonEmptySnippets): MergedSnippet {
  const [first] = snippets;
  const stacked = snippets.flatMap((s) => linesOf(s).map((l) => withCount({ cells: [...l.cells], raw_text: l.raw_text })));
  const { lines, beats_per_line } = fitToMode(stacked, first.grid_mode, first.beats_per_line);
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
