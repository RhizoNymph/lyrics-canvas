import { describe, it, expect } from 'vitest';
import {
  initEditorState,
  gridLineForRow,
  typeLine,
  insertRow,
  deleteRow,
  moveChunkInRow,
  insertRestInRow,
  removeCellsInRow,
  reorderRows,
  mergeSyllabified,
  needsSyllabify,
  rawTextForSave,
  setActiveRow,
} from './editorState';
import type { EditorRowsState } from './editorState';
import type { GridCell, GridData, GridLine } from '@/types';

const c = (syllable: string, word_index: number, stress = 0): GridCell => ({ syllable, word_index, stress });
const rest = (): GridCell => ({ rest: true });
const apiLine = (raw_text: string, cells: GridCell[]): GridLine => ({ cells, syllable_count: cells.filter((x) => !x.rest).length, raw_text });
const syls = (l: GridLine | null) => (l ? l.cells.map((x) => (x.rest ? '·' : x.syllable)) : null);
const texts = (s: EditorRowsState) => s.gridData?.lines.map((l) => l.raw_text) ?? null;

describe('initEditorState', () => {
  it('starts an empty snippet with one blank active row and no grid', () => {
    const s = initEditorState('', null);
    expect(s.lines).toEqual(['']);
    expect(s.gridData).toBeNull();
    expect(s.activeRow).toBe(0);
    expect(s.arranged.size).toBe(0);
  });

  it('keeps saved grid lines whose text matches and pins them as arranged', () => {
    const saved: GridData = { lines: [apiLine('hello world', [c('hel', 0, 1), c('lo', 0), rest(), c('world', 1, 1)])] };
    const s = initEditorState('hello world', saved);
    expect(s.activeRow).toBe(-1);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', 'lo', '·', 'world']);
    expect(s.arranged.has(0)).toBe(true);
    expect(gridLineForRow(s, 0)?.cells[0].word).toBe('hello');
  });

  it('falls back to a local estimate when the saved grid does not match, and skips blank rows', () => {
    const saved: GridData = { lines: [apiLine('old text', [c('old', 0), c('text', 1)])] };
    const s = initEditorState('hello\n\nworld', saved);
    expect(s.lines).toEqual(['hello', '', 'world']);
    expect(texts(s)).toEqual(['hello', 'world']);
    expect(s.arranged.size).toBe(0);
    expect(gridLineForRow(s, 1)).toBeNull();
    expect(gridLineForRow(s, 2)?.raw_text).toBe('world');
  });
});

describe('typeLine', () => {
  it('replaces the row grid line with a local estimate and un-arranges it', () => {
    const saved: GridData = { lines: [apiLine('hello', [c('hel', 0), rest(), c('lo', 0)])] };
    let s = initEditorState('hello', saved);
    s = typeLine(s, 0, 'hello there');
    expect(s.lines[0]).toBe('hello there');
    expect(s.arranged.has(0)).toBe(false);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', 'lo', 'there']);
  });

  it('adds and removes grid lines as rows become non-blank or blank', () => {
    let s = initEditorState('a\nb', null);
    s = typeLine(s, 0, '');
    expect(texts(s)).toEqual(['b']);
    expect(gridLineForRow(s, 1)?.raw_text).toBe('b');
    s = typeLine(s, 0, 'x');
    expect(texts(s)).toEqual(['x', 'b']);
    s = typeLine(s, 0, '');
    s = typeLine(s, 1, '');
    expect(s.gridData).toBeNull();
  });

  it('keeps other arranged rows mapped when an earlier row goes blank', () => {
    const saved: GridData = { lines: [apiLine('a', [c('a', 0)]), apiLine('b', [rest(), c('b', 0)])] };
    let s = initEditorState('a\nb', saved);
    s = typeLine(s, 0, '');
    expect(s.arranged.has(1)).toBe(true);
    expect(syls(gridLineForRow(s, 1))).toEqual(['·', 'b']);
  });
});

describe('insertRow / deleteRow', () => {
  it('insertRow adds a blank row below and shifts arranged indexes', () => {
    const saved: GridData = { lines: [apiLine('a', [c('a', 0)]), apiLine('b', [c('b', 0)])] };
    let s = initEditorState('a\nb', saved);
    s = insertRow(s, 0);
    expect(s.lines).toEqual(['a', '', 'b']);
    expect(s.activeRow).toBe(1);
    expect([...s.arranged].sort()).toEqual([0, 2]);
    expect(texts(s)).toEqual(['a', 'b']);
  });

  it('deleteRow removes the row and its grid line and shifts arranged indexes', () => {
    const saved: GridData = { lines: [apiLine('a', [c('a', 0)]), apiLine('b', [c('b', 0)])] };
    let s = initEditorState('a\nb', saved);
    s = deleteRow(s, 0);
    expect(s.lines).toEqual(['b']);
    expect(texts(s)).toEqual(['b']);
    expect([...s.arranged]).toEqual([0]);
    expect(s.activeRow).toBe(0);
  });

  it('deleteRow keeps at least one row', () => {
    const s = deleteRow(initEditorState('', null), 0);
    expect(s.lines).toEqual(['']);
  });
});

describe('moveChunkInRow', () => {
  it('moves cells, rebuilds the text into lines and marks the row arranged', () => {
    let s = initEditorState('hello world', null);
    s = moveChunkInRow(s, 0, 2, 3, 0); // "world" to the front
    expect(syls(gridLineForRow(s, 0))).toEqual(['world', 'hel', 'lo']);
    expect(gridLineForRow(s, 0)?.raw_text).toBe('world hello');
    expect(s.lines[0]).toBe('world hello');
    expect(s.arranged.has(0)).toBe(true);
  });

  it('is a no-op on a blank row', () => {
    const s = initEditorState('a\n\nb', null);
    expect(moveChunkInRow(s, 1, 0, 1, 0)).toBe(s);
  });
});

describe('insertRestInRow / removeCellsInRow', () => {
  it('inserts a rest, keeps the text and marks the row arranged', () => {
    let s = initEditorState('hello', null);
    s = insertRestInRow(s, 0, 1);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', '·', 'lo']);
    expect(s.lines[0]).toBe('hello');
    expect(s.arranged.has(0)).toBe(true);
  });

  it('removes a range of cells and rebuilds the text', () => {
    let s = initEditorState('hello', null);
    s = insertRestInRow(s, 0, 1);
    s = insertRestInRow(s, 0, 1);
    s = removeCellsInRow(s, 0, 1, 3);
    expect(syls(gridLineForRow(s, 0))).toEqual(['hel', 'lo']);
    s = removeCellsInRow(s, 0, 0, 1);
    expect(s.lines[0]).toBe('lo');
  });
});

describe('reorderRows', () => {
  const saved: GridData = { lines: [apiLine('a', [c('a', 0)]), apiLine('c', [rest(), c('c', 0)])] };

  it('moves a row and carries its grid line, arranged flag and active state', () => {
    let s = initEditorState('a\n\nc', saved);
    s = setActiveRow(s, 2);
    s = reorderRows(s, 2, 0);
    expect(s.lines).toEqual(['c', 'a', '']);
    expect(texts(s)).toEqual(['c', 'a']);
    expect(syls(gridLineForRow(s, 0))).toEqual(['·', 'c']);
    expect([...s.arranged].sort()).toEqual([0, 1]);
    expect(s.activeRow).toBe(0);
  });

  it('shifts the active row when another row moves across it', () => {
    let s = initEditorState('a\nb\nc', null);
    s = setActiveRow(s, 1);
    s = reorderRows(s, 2, 0);
    expect(s.lines).toEqual(['c', 'a', 'b']);
    expect(s.activeRow).toBe(2);
  });

  it('is a no-op out of range', () => {
    const s = initEditorState('a\nb', null);
    expect(reorderRows(s, 0, 2)).toBe(s);
    expect(reorderRows(s, 1, 1)).toBe(s);
  });
});

describe('mergeSyllabified', () => {
  it('replaces unarranged rows by matching text and attaches words', () => {
    let s = initEditorState('hello world\nbye', null);
    const result: GridData = {
      lines: [apiLine('hello world', [c('hel', 0, 1), c('lo', 0), c('world', 1, 1)]), apiLine('bye', [c('bye', 0, 1)])],
    };
    s = mergeSyllabified(s, result);
    expect(gridLineForRow(s, 0)?.cells[0].stress).toBe(1);
    expect(gridLineForRow(s, 0)?.cells[0].word).toBe('hello');
    expect(gridLineForRow(s, 1)?.cells[0].stress).toBe(1);
  });

  it('leaves arranged rows untouched and keeps rows whose text no longer matches', () => {
    let s = initEditorState('hello world\nbye', null);
    s = moveChunkInRow(s, 0, 2, 3, 0); // arranged: "world hello"
    s = typeLine(s, 1, 'byebye'); // changed after the request was sent
    const result: GridData = {
      lines: [apiLine('world hello', [c('WORLD', 0, 1), c('hel', 1, 1), c('lo', 1)]), apiLine('bye', [c('bye', 0, 1)])],
    };
    s = mergeSyllabified(s, result);
    expect(syls(gridLineForRow(s, 0))).toEqual(['world', 'hel', 'lo']);
    expect(gridLineForRow(s, 1)?.raw_text).toBe('byebye');
  });

  it('matches rows in order when two rows share the same text', () => {
    let s = initEditorState('la\nla', null);
    const result: GridData = { lines: [apiLine('la', [c('la', 0, 1)]), apiLine('la', [c('la', 0, 2)])] };
    s = mergeSyllabified(s, result);
    expect(gridLineForRow(s, 0)?.cells[0].stress).toBe(1);
    expect(gridLineForRow(s, 1)?.cells[0].stress).toBe(2);
  });
});

describe('needsSyllabify', () => {
  it('is true only when some non-blank row is not arranged', () => {
    const saved: GridData = { lines: [apiLine('a', [c('a', 0)])] };
    expect(needsSyllabify(initEditorState('a', saved))).toBe(false);
    expect(needsSyllabify(initEditorState('a\nb', saved))).toBe(true);
    expect(needsSyllabify(initEditorState('', null))).toBe(false);
  });
});

describe('rawTextForSave', () => {
  it('derives arranged rows from the grid and keeps typed text elsewhere', () => {
    let s = initEditorState('hello world\n\nbye', null);
    s = moveChunkInRow(s, 0, 2, 3, 0);
    expect(rawTextForSave(s)).toBe('world hello\n\nbye');
  });
});
