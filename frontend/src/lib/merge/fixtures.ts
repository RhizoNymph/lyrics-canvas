import type { GridCell, GridLine, Snippet, Tag } from '@/types';

/** Cells for a line of text: one syllable per word, "." is a rest. */
export function cellsFor(text: string): GridCell[] {
  let word = 0;
  return text.split(/\s+/).filter(Boolean).map((tok) => {
    if (tok === '.') return { rest: true };
    const cell: GridCell = { syllable: tok, stress: 1, word_index: word };
    word += 1;
    return cell;
  });
}

export function line(text: string, cellText = text): GridLine {
  const cells = cellsFor(cellText);
  return { cells, syllable_count: cells.filter((c) => !c.rest).length, raw_text: text };
}

export function tag(id: string): Tag {
  return { id, canvas_id: 'c1', name: id, color: '#000' };
}

interface SnippetOverrides extends Partial<Omit<Snippet, 'id' | 'raw_text'>> {}

/** A snippet whose grid is one line per text line, one cell per word. */
export function snippet(id: string, text: string, overrides: SnippetOverrides = {}): Snippet {
  const lines = text.split('\n').filter((l) => l.trim()).map((l) => line(l.trim()));
  return {
    id,
    canvas_id: 'c1',
    snippet_type: 'verse',
    grid_mode: 'auto',
    beats_per_line: null,
    grid_data: { lines },
    raw_text: text,
    position_x: 0,
    position_y: 0,
    width: 200,
    height: 100,
    z_index: 0,
    group_id: null,
    tags: [],
    created_at: '',
    updated_at: '',
    ...overrides,
  };
}
