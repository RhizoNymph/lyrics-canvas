import { describe, it, expect } from 'vitest';
import {
  initEditorState,
  gridLineForRow,
  typeLine,
  insertRestInRow,
  moveChunkInRow,
  mergeSyllabified,
  nudgeSelection,
  rawTextForSave,
} from './editorState';
import type { EditorRowsState } from './editorState';
import type { GridCell, GridData, GridLine } from '@/types';
import { lineWords } from '@/lib/grid';

const c = (syllable: string, word_index: number): GridCell => ({ syllable, word_index });
const rest = (): GridCell => ({ rest: true });
const apiLine = (raw_text: string, cells: GridCell[]): GridLine => ({ cells, syllable_count: cells.filter((x) => !x.rest).length, raw_text });
const syls = (l: GridLine | null) => (l ? l.cells.map((x) => (x.rest ? '·' : x.syllable)) : null);

/** The reducer invariants of grid_rearrange.md, with rest marks. */
function expectInvariants(s: EditorRowsState) {
  const nonBlank = s.lines.filter((l) => l.trim() !== '');
  expect(s.gridData?.lines.length ?? 0).toBe(nonBlank.length);
  s.lines.forEach((text, row) => {
    const line = gridLineForRow(s, row);
    if (!text.trim()) return expect(line).toBeNull();
    expect(line?.raw_text.trim()).toBe(text.trim());
    // Word indexes count words only: every index addresses a word token of the row text.
    const words = lineWords(text);
    for (const cell of line?.cells ?? []) {
      if (cell.rest) continue;
      expect(typeof cell.word_index === 'number' && cell.word_index < words.length).toBe(true);
    }
  });
}

describe('rest marks in rows', () => {
  it('typing _ makes rest cells and the row stays as typed', () => {
    const s = typeLine(initEditorState('', null), 0, 'I _ go');
    expect(syls(gridLineForRow(s, 0))).toEqual(['I', '·', 'go']);
    expect(s.lines[0]).toBe('I _ go');
    expectInvariants(s);
  });

  it('a rest-only row is non-blank and has a grid line of rests', () => {
    const s = typeLine(initEditorState('a', null), 0, '_ _');
    expect(syls(gridLineForRow(s, 0))).toEqual(['·', '·']);
    expectInvariants(s);
  });

  it('inserting a rest writes _ into the row text', () => {
    let s = initEditorState('I go', null);
    s = insertRestInRow(s, 0, 1);
    expect(s.lines[0]).toBe('I _ go');
    expect(rawTextForSave(s)).toBe('I _ go');
    expectInvariants(s);
  });

  it('moving a syllable over a rest moves the _ in the text', () => {
    let s = typeLine(initEditorState('', null), 0, 'I _ go');
    s = moveChunkInRow(s, 0, 0, 1, 1);
    expect(s.lines[0]).toBe('_ I go');
    expectInvariants(s);
  });

  it('opens a saved canonical row (with _) arranged', () => {
    const saved: GridData = { lines: [apiLine('I _ go', [c('I', 0), rest(), c('go', 1)])] };
    const s = initEditorState('I _ go', saved);
    expect(s.arranged.has(0)).toBe(true);
    expect(gridLineForRow(s, 0)?.cells.map((x) => x.word)).toEqual(['I', undefined, 'go']);
    expectInvariants(s);
  });

  it('opens a legacy row (grid rests, no _ in the text) arranged with its rests, and saves canonical text', () => {
    const saved: GridData = { lines: [apiLine('I go', [c('I', 0), rest(), rest(), c('go', 1)])] };
    const s = initEditorState('I go', saved);
    expect(s.arranged.has(0)).toBe(true);
    expect(syls(gridLineForRow(s, 0))).toEqual(['I', '·', '·', 'go']);
    expect(s.lines[0]).toBe('I go');
    expectInvariants(s);
    expect(rawTextForSave(s)).toBe('I _ _ go');
  });

  it('matches a legacy line whose saved raw_text differs only in rest marks or spacing', () => {
    const saved: GridData = { lines: [apiLine('I  go', [c('I', 0), rest(), c('go', 1)])] };
    const s = initEditorState('I _ go', saved);
    expect(s.arranged.has(0)).toBe(true);
    expect(gridLineForRow(s, 0)?.raw_text).toBe('I _ go');
    expectInvariants(s);
  });

  it('does not match a rest-only row against a different rest-only line', () => {
    const saved: GridData = { lines: [apiLine('__', [rest(), rest()])] };
    const s = initEditorState('_', saved);
    expect(s.arranged.has(0)).toBe(false);
    expect(syls(gridLineForRow(s, 0))).toEqual(['·']);
  });

  it('still rejects a saved line with different words', () => {
    const saved: GridData = { lines: [apiLine('I go', [c('I', 0), rest(), c('go', 1)])] };
    const s = initEditorState('I went', saved);
    expect(s.arranged.has(0)).toBe(false);
  });

  it('a syllabify result keeps the rests the user typed', () => {
    let s = typeLine(initEditorState('', null), 0, 'hey _ you');
    s = mergeSyllabified(s, { lines: [apiLine('hey _ you', [{ ...c('hey', 0), stress: 1 }, rest(), { ...c('you', 1), stress: 1 }])] });
    expect(syls(gridLineForRow(s, 0))).toEqual(['hey', '·', 'you']);
    expect(gridLineForRow(s, 0)?.cells[2].word).toBe('you');
    expectInvariants(s);
  });
});

describe('nudgeSelection', () => {
  const sel = (start: number, end: number) => ({ row: 0, start, end });

  it('moves the chunk one cell right when there is a cell after it', () => {
    const s = initEditorState('hello world', null); // hel lo world
    const out = nudgeSelection(s, sel(0, 1), 1);
    expect(syls(gridLineForRow(out.state, 0))).toEqual(['lo', 'hel', 'world']);
    expect(out.selection).toEqual(sel(1, 2));
    expectInvariants(out.state);
  });

  it('inserts a rest before the selection at the end of the line; the selection shifts right', () => {
    const s = initEditorState('I go', null);
    const out = nudgeSelection(s, sel(1, 2), 1);
    expect(syls(gridLineForRow(out.state, 0))).toEqual(['I', '·', 'go']);
    expect(out.selection).toEqual(sel(2, 3));
    expect(out.state.lines[0]).toBe('I _ go');
    expect(out.state.arranged.has(0)).toBe(true);
    expectInvariants(out.state);
  });

  it('keeps pushing right with more rests on repeated nudges', () => {
    let out = nudgeSelection(initEditorState('I go', null), sel(1, 2), 1);
    out = nudgeSelection(out.state, out.selection, 1);
    expect(out.state.lines[0]).toBe('I _ _ go');
    expect(out.selection).toEqual(sel(3, 4));
  });

  it('pushes a whole-line selection right', () => {
    const out = nudgeSelection(initEditorState('I go', null), sel(0, 2), 1);
    expect(out.state.lines[0]).toBe('_ I go');
    expect(out.selection).toEqual(sel(1, 3));
  });

  it('moves left as before and does nothing at index 0', () => {
    const s = initEditorState('I go', null);
    const left = nudgeSelection(s, sel(1, 2), -1);
    expect(left.state.lines[0]).toBe('go I');
    expect(left.selection).toEqual(sel(0, 1));
    const none = nudgeSelection(s, sel(0, 1), -1);
    expect(none.state).toBe(s);
    expect(none.selection).toEqual(sel(0, 1));
  });

  it('is a no-op on a row without a grid line', () => {
    const s = initEditorState('a\n\nb', null);
    const out = nudgeSelection(s, { row: 1, start: 0, end: 1 }, 1);
    expect(out.state).toBe(s);
  });
});
