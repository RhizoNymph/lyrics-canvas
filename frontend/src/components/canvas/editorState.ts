/**
 * Pure reducers for the inline grid editor's row state.
 *
 * Invariant: `gridData.lines` holds exactly one line per non-blank entry of
 * `lines`, in order, with `raw_text` equal to the trimmed row text. Blank rows
 * have no grid line. `gridData` is null when no row is non-blank. Rows in
 * `arranged` were laid out by hand (or loaded from a saved grid) and are never
 * replaced by a syllabify result until their text is typed again.
 *
 * Row text carries rest marks (`_`, lib/grid/restMarks.ts): typed `_` become rest cells, and grid edits
 * write their rests back as `_` (the row text of an arranged row is its grid's canonical text).
 *
 * `caret` asks the view to put the caret of row `caret.row` at `caret.offset` when it focuses
 * that row. Structural edits (insert, delete, split, join) issue a fresh object every time, so the
 * view can react to identity; typing, grid edits and syllabify merges keep it, and focusing another
 * row or reordering clears it (the caret then goes to the end of the row, the default).
 */
import type { GridData, GridLine } from '@/types';
import { attachWords, displayLine, insertRest, localSyllabifyLine, moveChunk, removeCell, withRebuiltText } from '@/lib/grid';

export interface EditorRowsState {
  lines: string[];
  gridData: GridData | null;
  arranged: ReadonlySet<number>;
  activeRow: number;
  caret: CaretRequest | null;
}

export interface CaretRequest {
  readonly row: number;
  readonly offset: number;
}

const caretAt = (row: number, offset: number): CaretRequest => ({ row, offset });

interface Row {
  text: string;
  grid: GridLine | null;
  arranged: boolean;
}

function toRows(state: EditorRowsState): Row[] {
  const gridLines = state.gridData?.lines ?? [];
  let gi = 0;
  return state.lines.map((text, row) => {
    const blank = !text.trim();
    const grid = blank ? null : (gridLines[gi++] ?? localSyllabifyLine(text));
    return { text, grid, arranged: !blank && state.arranged.has(row) };
  });
}

function fromRows(rows: Row[], activeRow: number, caret: CaretRequest | null): EditorRowsState {
  const lines = rows.map((r) => r.text);
  const gridLines = rows.flatMap((r) => (r.grid ? [r.grid] : []));
  const arranged = new Set<number>();
  rows.forEach((r, i) => {
    if (r.arranged && r.grid) arranged.add(i);
  });
  return { lines, gridData: gridLines.length > 0 ? { lines: gridLines } : null, arranged, activeRow, caret };
}

/**
 * Whether a saved grid line still belongs to a row: same trimmed text, or the same display text (rest
 * marks and spacing ignored), so rows saved before rest marks (grid rests, no `_` in the text) still
 * match. A rest-only row (empty display text) needs the exact text.
 */
function savedLineMatches(saved: GridLine, text: string): boolean {
  if (saved.raw_text.trim() === text.trim()) return true;
  const display = displayLine(text);
  return display !== '' && displayLine(saved.raw_text) === display;
}

/**
 * Build the state from a snippet's text and saved grid; matching saved lines are kept and pinned. A kept
 * line takes the row's text as its `raw_text`; the next save writes the canonical text (with `_`).
 */
export function initEditorState(rawText: string, savedGrid: GridData | null): EditorRowsState {
  const lines = rawText ? rawText.split('\n') : [''];
  const saved = savedGrid?.lines ?? [];
  let si = 0;
  const rows: Row[] = lines.map((text) => {
    if (!text.trim()) return { text, grid: null, arranged: false };
    const candidate = saved[si];
    if (candidate && savedLineMatches(candidate, text)) {
      si++;
      const raw_text = text.trim();
      const grid = attachWords(candidate.raw_text === raw_text ? candidate : { ...candidate, raw_text });
      return { text, grid, arranged: true };
    }
    return { text, grid: localSyllabifyLine(text), arranged: false };
  });
  return fromRows(rows, rawText ? -1 : 0, null);
}

export function gridLineForRow(state: EditorRowsState, row: number): GridLine | null {
  if (row < 0 || row >= state.lines.length || !state.lines[row].trim()) return null;
  let gi = 0;
  for (let i = 0; i < row; i++) if (state.lines[i].trim()) gi++;
  return state.gridData?.lines[gi] ?? null;
}

export function setActiveRow(state: EditorRowsState, activeRow: number): EditorRowsState {
  return state.activeRow === activeRow ? state : { ...state, activeRow, caret: null };
}

/** The user typed into a row: its grid becomes a local estimate and it is no longer arranged. */
export function typeLine(state: EditorRowsState, row: number, text: string): EditorRowsState {
  const rows = toRows(state);
  if (row < 0 || row >= rows.length) return state;
  rows[row] = { text, grid: text.trim() ? localSyllabifyLine(text) : null, arranged: false };
  return fromRows(rows, state.activeRow, state.caret);
}

const BLANK: Row = { text: '', grid: null, arranged: false };

function edited(text: string): Row {
  return { text, grid: text.trim() ? localSyllabifyLine(text) : null, arranged: false };
}

/** Insert a blank row after `afterRow` and make it active. */
export function insertRow(state: EditorRowsState, afterRow: number): EditorRowsState {
  const rows = toRows(state);
  rows.splice(afterRow + 1, 0, BLANK);
  return fromRows(rows, afterRow + 1, caretAt(afterRow + 1, 0));
}

/** Remove a row (keeping at least one) and activate the row above, caret at its end. */
export function deleteRow(state: EditorRowsState, row: number): EditorRowsState {
  const rows = toRows(state);
  if (row < 0 || row >= rows.length) return state;
  if (rows.length === 1) return fromRows([BLANK], 0, caretAt(0, 0));
  rows.splice(row, 1);
  const active = Math.max(0, row - 1);
  return fromRows(rows, active, caretAt(active, rows[active].text.length));
}

/**
 * Enter at `caret` in `row`: the text before stays (trailing whitespace trimmed), the text after
 * becomes a new row directly below (leading whitespace trimmed), which becomes active with the
 * caret at its start. Both halves are re-estimated and lose `arranged`. With only whitespace after
 * the caret this is `insertRow` (the row is untouched); with only whitespace before it, a blank
 * row opens above and the row moves down unchanged (keeping its grid and `arranged`).
 */
export function splitRow(state: EditorRowsState, row: number, caret: number): EditorRowsState {
  const rows = toRows(state);
  const current = rows[row];
  if (!current) return state;
  const at = Math.min(Math.max(caret, 0), current.text.length);
  const before = current.text.slice(0, at);
  const after = current.text.slice(at);
  if (!after.trim()) return insertRow(state, row);
  if (!before.trim()) {
    rows.splice(row, 1, BLANK, { ...current, text: after.trimStart() });
  } else {
    rows.splice(row, 1, edited(before.trimEnd()), edited(after.trimStart()));
  }
  return fromRows(rows, row + 1, caretAt(row + 1, 0));
}

/**
 * Join row `upper + 1` onto `upper`, with one space between unless either side is blank or
 * already has whitespace at the seam. A blank side contributes nothing: the other row is kept
 * as is (grid and `arranged` included). Otherwise the joined row is re-estimated and loses
 * `arranged`. Returns the rows and the seam offsets (before / after the separator).
 */
function join(state: EditorRowsState, upper: number): { rows: Row[]; seam: number; joinPoint: number } | null {
  const rows = toRows(state);
  const top = rows[upper];
  const bottom = rows[upper + 1];
  if (upper < 0 || !top || !bottom) return null;
  let merged: Row;
  let seam: number;
  let joinPoint: number;
  if (!bottom.text.trim()) {
    merged = top;
    seam = joinPoint = top.text.length;
  } else if (!top.text.trim()) {
    merged = bottom;
    seam = joinPoint = 0;
  } else {
    const sep = /\s$/.test(top.text) || /^\s/.test(bottom.text) ? '' : ' ';
    merged = edited(top.text + sep + bottom.text);
    seam = top.text.length;
    joinPoint = seam + sep.length;
  }
  rows.splice(upper, 2, merged);
  return { rows, seam, joinPoint };
}

/** Backspace at caret 0 of `row`: join it onto the row above; caret at the join point. */
export function joinWithPrevious(state: EditorRowsState, row: number): EditorRowsState {
  const joined = join(state, row - 1);
  if (!joined) return state;
  return fromRows(joined.rows, row - 1, caretAt(row - 1, joined.joinPoint));
}

/** Delete at the end of `row`: join the row below onto it; the caret stays where it was. */
export function joinWithNext(state: EditorRowsState, row: number): EditorRowsState {
  const joined = join(state, row);
  if (!joined) return state;
  return fromRows(joined.rows, row, caretAt(row, joined.seam));
}

function updateGrid(state: EditorRowsState, row: number, fn: (line: GridLine) => GridLine): EditorRowsState {
  const rows = toRows(state);
  const current = rows[row];
  if (!current || !current.grid) return state;
  const next = fn(current.grid);
  if (next === current.grid) return state;
  const grid = withRebuiltText(next);
  rows[row] = { text: grid.raw_text, grid, arranged: true };
  return fromRows(rows, state.activeRow, state.caret);
}

/** Move cells `[start, end)` of a row so the chunk starts at `to` (see `moveChunk`). */
export function moveChunkInRow(state: EditorRowsState, row: number, start: number, end: number, to: number): EditorRowsState {
  return updateGrid(state, row, (line) => moveChunk(line, start, end, to));
}

export function insertRestInRow(state: EditorRowsState, row: number, at: number): EditorRowsState {
  return updateGrid(state, row, (line) => insertRest(line, at));
}

/** Selected cells `[start, end)` of one row. */
export interface CellSelection {
  readonly row: number;
  readonly start: number;
  /** Exclusive. */
  readonly end: number;
}

/**
 * Left / Right arrow on a selection: move the chunk one cell. Right with nothing after the chunk inserts a
 * rest before it instead (it moves one cell right, as with `+·`); left at index 0 does nothing.
 */
export function nudgeSelection(
  state: EditorRowsState,
  selection: CellSelection,
  direction: -1 | 1,
): { state: EditorRowsState; selection: CellSelection } {
  const line = gridLineForRow(state, selection.row);
  const unchanged = { state, selection };
  if (!line || selection.start < 0 || selection.end > line.cells.length || selection.start >= selection.end) return unchanged;
  const { row, start, end } = selection;
  if (direction === 1 && end === line.cells.length) {
    return { state: insertRestInRow(state, row, start), selection: { row, start: start + 1, end: end + 1 } };
  }
  if (direction === -1 && start === 0) return unchanged;
  const to = start + direction;
  return { state: moveChunkInRow(state, row, start, end, to), selection: { row, start: to, end: end + direction } };
}

/** Remove cells `[start, end)` of a row. */
export function removeCellsInRow(state: EditorRowsState, row: number, start: number, end: number): EditorRowsState {
  return updateGrid(state, row, (line) => {
    let out = line;
    for (let i = end - 1; i >= start; i--) out = removeCell(out, i);
    return out;
  });
}

/** Move row `from` to index `to`, carrying its grid line and arranged flag; the active row follows its line. */
export function reorderRows(state: EditorRowsState, from: number, to: number): EditorRowsState {
  const n = state.lines.length;
  if (from === to || from < 0 || from >= n || to < 0 || to >= n) return state;
  const rows = toRows(state);
  const [moved] = rows.splice(from, 1);
  rows.splice(to, 0, moved);
  let activeRow = state.activeRow;
  if (activeRow === from) activeRow = to;
  else if (from < activeRow && activeRow <= to) activeRow -= 1;
  else if (to <= activeRow && activeRow < from) activeRow += 1;
  return fromRows(rows, activeRow, null);
}

/**
 * Apply a syllabify result. Each unarranged row takes the first unconsumed
 * result line with the same text; arranged rows and rows whose text changed
 * since the request keep their current grid line.
 */
export function mergeSyllabified(state: EditorRowsState, result: GridData): EditorRowsState {
  const rows = toRows(state);
  const consumed = new Set<number>();
  const next = rows.map((row) => {
    if (!row.grid || row.arranged) return row;
    const wanted = row.text.trim();
    const match = result.lines.findIndex((line, i) => !consumed.has(i) && line.raw_text.trim() === wanted);
    if (match < 0) return row;
    consumed.add(match);
    return { ...row, grid: attachWords(result.lines[match]) };
  });
  return fromRows(next, state.activeRow, state.caret);
}

/** True when some non-blank row still wants an API syllabification. */
export function needsSyllabify(state: EditorRowsState): boolean {
  return state.lines.some((text, row) => text.trim() !== '' && !state.arranged.has(row));
}

/** The text to persist: arranged rows are spelled from their grid, others as typed. */
export function rawTextForSave(state: EditorRowsState): string {
  return toRows(state)
    .map((row) => (row.arranged && row.grid ? withRebuiltText(row.grid).raw_text : row.text))
    .join('\n');
}
