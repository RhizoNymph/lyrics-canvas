import { describe, it, expect } from 'vitest';
import {
  initEditorState,
  gridLineForRow,
  typeLine,
  insertRow,
  deleteRow,
  splitRow,
  joinWithPrevious,
  joinWithNext,
  moveChunkInRow,
  mergeSyllabified,
  needsSyllabify,
  rawTextForSave,
  reorderRows,
  setActiveRow,
} from './editorState';
import type { EditorRowsState } from './editorState';
import type { GridCell, GridData, GridLine } from '@/types';
import { localSyllabifyLine } from '@/lib/grid';

const c = (syllable: string, word_index: number, stress = 0): GridCell => ({ syllable, word_index, stress });
const rest = (): GridCell => ({ rest: true });
const apiLine = (raw_text: string, cells: GridCell[]): GridLine => ({ cells, syllable_count: cells.filter((x) => !x.rest).length, raw_text });
const syls = (l: GridLine | null) => (l ? l.cells.map((x) => (x.rest ? '·' : x.syllable)) : null);
const texts = (s: EditorRowsState) => s.gridData?.lines.map((l) => l.raw_text) ?? null;
const arranged = (s: EditorRowsState) => [...s.arranged].sort((a, b) => a - b);

/** Every non-blank row has exactly one grid line whose raw_text is its trimmed text. */
function expectInvariant(s: EditorRowsState) {
  const nonBlank = s.lines.filter((l) => l.trim());
  expect(texts(s) ?? []).toEqual(nonBlank.map((l) => l.trim()));
  for (const r of s.arranged) expect(s.lines[r].trim()).not.toBe('');
}

describe('splitRow', () => {
  it('splits mid-line: text before stays, text after becomes the next row, caret at its start', () => {
    let s = initEditorState('hello world\nbye', null);
    s = splitRow(s, 0, 6);
    expect(s.lines).toEqual(['hello', 'world', 'bye']);
    expect(texts(s)).toEqual(['hello', 'world', 'bye']);
    expect(s.activeRow).toBe(1);
    expect(s.caret).toEqual({ row: 1, offset: 0 });
    expectInvariant(s);
  });

  it('trims trailing whitespace of the kept part and leading whitespace of the moved part', () => {
    const s = splitRow(initEditorState('one   two  three', null), 0, 5);
    expect(s.lines).toEqual(['one', 'two  three']);
    expect(s.caret).toEqual({ row: 1, offset: 0 });
  });

  it('splits mid-word', () => {
    const s = splitRow(initEditorState('yesterday', null), 0, 3);
    expect(s.lines).toEqual(['yes', 'terday']);
    expect(gridLineForRow(s, 0)).toEqual(localSyllabifyLine('yes'));
    expect(gridLineForRow(s, 1)).toEqual(localSyllabifyLine('terday'));
  });

  it('with the caret at the end inserts a blank row below and leaves the row untouched', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    const s0 = initEditorState('hello', saved);
    const s = splitRow(s0, 0, 5);
    expect(s.lines).toEqual(['hello', '']);
    expect(s.activeRow).toBe(1);
    expect(s.caret).toEqual({ row: 1, offset: 0 });
    expect(arranged(s)).toEqual([0]);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', '·', 'lo']);
    // Same result as the plain insert.
    expect(s.lines).toEqual(insertRow(s0, 0).lines);
  });

  it('treats a caret followed only by whitespace as the end', () => {
    const s = splitRow(initEditorState('hello   ', null), 0, 5);
    expect(s.lines).toEqual(['hello   ', '']);
    expect(s.activeRow).toBe(1);
  });

  it('clamps an out-of-range caret to the end', () => {
    const s = splitRow(initEditorState('hello', null), 0, 99);
    expect(s.lines).toEqual(['hello', '']);
  });

  it('with the caret at 0 opens a blank row above and carries the row (grid and arranged) down', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    const s = splitRow(initEditorState('hello', saved), 0, 0);
    expect(s.lines).toEqual(['', 'hello']);
    expect(arranged(s)).toEqual([1]);
    expect(syls(gridLineForRow(s, 1))).toEqual(['hel', '·', 'lo']);
    expect(s.activeRow).toBe(1);
    expect(s.caret).toEqual({ row: 1, offset: 0 });
    expectInvariant(s);
  });

  it('with only whitespace before the caret drops that whitespace and carries the row down', () => {
    const s = splitRow(initEditorState('  hello', null), 0, 2);
    expect(s.lines).toEqual(['', 'hello']);
    expect(s.caret).toEqual({ row: 1, offset: 0 });
  });

  it('on an empty row inserts a blank row', () => {
    const s = splitRow(initEditorState('a\n\nb', null), 1, 0);
    expect(s.lines).toEqual(['a', '', '', 'b']);
    expect(s.activeRow).toBe(2);
    expectInvariant(s);
  });

  it('un-arranges both halves of a split arranged row and gives them local estimates', () => {
    const saved: GridData = {
      lines: [apiLine('a', [c('a', 0)]), apiLine('hello world', [c('hel', 0), rest(), c('lo', 0), c('world', 1)]), apiLine('z', [rest(), c('z', 0)])],
    };
    const s = splitRow(initEditorState('a\nhello world\nz', saved), 1, 5);
    expect(s.lines).toEqual(['a', 'hello', 'world', 'z']);
    expect(arranged(s)).toEqual([0, 3]);
    expect(gridLineForRow(s, 1)).toEqual(localSyllabifyLine('hello'));
    expect(gridLineForRow(s, 2)).toEqual(localSyllabifyLine('world'));
    expect(syls(gridLineForRow(s, 3))).toEqual(['·', 'z']);
    expect(needsSyllabify(s)).toBe(true);
    expectInvariant(s);
  });

  it('splits the last row', () => {
    const s = splitRow(initEditorState('a\nb c', null), 1, 1);
    expect(s.lines).toEqual(['a', 'b', 'c']);
    expect(s.activeRow).toBe(2);
  });

  it('is a no-op for an out-of-range row', () => {
    const s = initEditorState('a', null);
    expect(splitRow(s, 3, 0)).toBe(s);
    expect(splitRow(s, -1, 0)).toBe(s);
  });

  it('lets a syllabify result refine the new rows', () => {
    let s = splitRow(initEditorState('hello world', null), 0, 5);
    s = mergeSyllabified(s, { lines: [apiLine('hello', [c('HEL', 0, 1), c('lo', 0)]), apiLine('world', [c('WORLD', 0, 1)])] });
    expect(syls(gridLineForRow(s, 0))).toEqual(['HEL', 'lo']);
    expect(syls(gridLineForRow(s, 1))).toEqual(['WORLD']);
  });
});

describe('joinWithPrevious', () => {
  it('joins a row onto the previous one with a single space; caret at the join point', () => {
    let s = initEditorState('hello\nworld\nbye', null);
    s = setActiveRow(s, 1);
    s = joinWithPrevious(s, 1);
    expect(s.lines).toEqual(['hello world', 'bye']);
    expect(texts(s)).toEqual(['hello world', 'bye']);
    expect(s.activeRow).toBe(0);
    expect(s.caret).toEqual({ row: 0, offset: 6 });
    expectInvariant(s);
  });

  it('adds no space when the previous row already ends with one', () => {
    const s = joinWithPrevious(initEditorState('hello \nworld', null), 1);
    expect(s.lines).toEqual(['hello world']);
    expect(s.caret).toEqual({ row: 0, offset: 6 });
  });

  it('adds no space when the joined row already starts with one', () => {
    const s = joinWithPrevious(initEditorState('hello\n  world', null), 1);
    expect(s.lines).toEqual(['hello  world']);
    expect(s.caret).toEqual({ row: 0, offset: 5 });
  });

  it('keeps multiple spaces as they are', () => {
    const s = joinWithPrevious(initEditorState('a  \n  b', null), 1);
    expect(s.lines).toEqual(['a    b']);
  });

  it('on an empty row deletes it and puts the caret at the end of the previous row', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    const s = joinWithPrevious(initEditorState('hello\n', saved), 1);
    expect(s.lines).toEqual(['hello']);
    expect(arranged(s)).toEqual([0]);
    expect(s.caret).toEqual({ row: 0, offset: 5 });
    expect(s.activeRow).toBe(0);
  });

  it('onto an empty previous row moves the row up keeping its grid and arranged flag; caret at 0', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    const s = joinWithPrevious(initEditorState('\nhello', saved), 1);
    expect(s.lines).toEqual(['hello']);
    expect(arranged(s)).toEqual([0]);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', '·', 'lo']);
    expect(s.caret).toEqual({ row: 0, offset: 0 });
  });

  it('un-arranges the joined row and re-estimates its grid', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)]), apiLine('world', [rest(), c('world', 0)]), apiLine('z', [c('z', 0)])] };
    const s = joinWithPrevious(initEditorState('hello\nworld\nz', saved), 1);
    expect(s.lines).toEqual(['hello world', 'z']);
    expect(arranged(s)).toEqual([1]);
    expect(gridLineForRow(s, 0)).toEqual(localSyllabifyLine('hello world'));
    expect(rawTextForSave(s)).toBe('hello world\nz');
    expectInvariant(s);
  });

  it('is a no-op on the first row or out of range', () => {
    const s = initEditorState('a\nb', null);
    expect(joinWithPrevious(s, 0)).toBe(s);
    expect(joinWithPrevious(s, 2)).toBe(s);
  });

  it('round-trips with splitRow at the join point', () => {
    const s0 = initEditorState('hello\nworld', null);
    const joined = joinWithPrevious(s0, 1);
    const split = splitRow(joined, 0, joined.caret?.offset ?? 0);
    expect(split.lines).toEqual(['hello', 'world']);
  });
});

describe('joinWithNext', () => {
  it('joins the next row onto this one; caret stays at the old end', () => {
    const s = joinWithNext(initEditorState('hello\nworld\nbye', null), 0);
    expect(s.lines).toEqual(['hello world', 'bye']);
    expect(s.activeRow).toBe(0);
    expect(s.caret).toEqual({ row: 0, offset: 5 });
    expectInvariant(s);
  });

  it('removes an empty next row without touching this row', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    const s = joinWithNext(initEditorState('hello\n', saved), 0);
    expect(s.lines).toEqual(['hello']);
    expect(arranged(s)).toEqual([0]);
    expect(s.caret).toEqual({ row: 0, offset: 5 });
  });

  it('pulls a row up into an empty row', () => {
    const s = joinWithNext(initEditorState('\nworld', null), 0);
    expect(s.lines).toEqual(['world']);
    expect(s.caret).toEqual({ row: 0, offset: 0 });
  });

  it('is a no-op on the last row', () => {
    const s = initEditorState('a\nb', null);
    expect(joinWithNext(s, 1)).toBe(s);
  });
});

describe('caret requests', () => {
  it('survive typing and syllabify merges but are cleared by focusing another row or reordering', () => {
    let s = splitRow(initEditorState('hello world', null), 0, 5);
    const req = s.caret;
    s = typeLine(s, 1, 'worlds');
    expect(s.caret).toBe(req);
    s = mergeSyllabified(s, { lines: [] });
    expect(s.caret).toBe(req);
    s = moveChunkInRow(s, 0, 0, 1, 1);
    expect(s.caret).toBe(req);
    expect(setActiveRow(s, 0).caret).toBeNull();
    expect(reorderRows(s, 0, 1).caret).toBeNull();
  });

  it('are new objects on each structural edit so repeated identical requests are distinguishable', () => {
    const a = joinWithNext(initEditorState('hello\n\n', null), 0);
    const b = joinWithNext(a, 0);
    expect(b.caret).toEqual(a.caret);
    expect(b.caret).not.toBe(a.caret);
  });

  it('insertRow and deleteRow request the caret at the start of the new row / end of the row above', () => {
    expect(insertRow(initEditorState('a', null), 0).caret).toEqual({ row: 1, offset: 0 });
    expect(deleteRow(initEditorState('abc\nx', null), 1).caret).toEqual({ row: 0, offset: 3 });
  });

  it('start out empty', () => {
    expect(initEditorState('a', null).caret).toBeNull();
  });
});
