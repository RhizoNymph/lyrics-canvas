/**
 * Pure reducers for the inline grid editor's row state.
 *
 * Invariant: `gridData.lines` holds exactly one line per non-blank entry of
 * `lines`, in order, with `raw_text` equal to the trimmed row text. Blank rows
 * have no grid line. `gridData` is null when no row is non-blank. Rows in
 * `arranged` were laid out by hand (or loaded from a saved grid) and are never
 * replaced by a syllabify result until their text is typed again.
 */
import type { GridData, GridLine } from '@/types';
import { attachWords, insertRest, localSyllabifyLine, moveChunk, removeCell, withRebuiltText } from '@/lib/grid';

export interface EditorRowsState {
  lines: string[];
  gridData: GridData | null;
  arranged: ReadonlySet<number>;
  activeRow: number;
}

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

function fromRows(rows: Row[], activeRow: number): EditorRowsState {
  const lines = rows.map((r) => r.text);
  const gridLines = rows.flatMap((r) => (r.grid ? [r.grid] : []));
  const arranged = new Set<number>();
  rows.forEach((r, i) => {
    if (r.arranged && r.grid) arranged.add(i);
  });
  return { lines, gridData: gridLines.length > 0 ? { lines: gridLines } : null, arranged, activeRow };
}

/** Build the state from a snippet's text and saved grid; matching saved lines are kept and pinned. */
export function initEditorState(rawText: string, savedGrid: GridData | null): EditorRowsState {
  const lines = rawText ? rawText.split('\n') : [''];
  const saved = savedGrid?.lines ?? [];
  let si = 0;
  const rows: Row[] = lines.map((text) => {
    if (!text.trim()) return { text, grid: null, arranged: false };
    const candidate = saved[si];
    if (candidate && candidate.raw_text.trim() === text.trim()) {
      si++;
      return { text, grid: attachWords(candidate), arranged: true };
    }
    return { text, grid: localSyllabifyLine(text), arranged: false };
  });
  return fromRows(rows, rawText ? -1 : 0);
}

export function gridLineForRow(state: EditorRowsState, row: number): GridLine | null {
  if (row < 0 || row >= state.lines.length || !state.lines[row].trim()) return null;
  let gi = 0;
  for (let i = 0; i < row; i++) if (state.lines[i].trim()) gi++;
  return state.gridData?.lines[gi] ?? null;
}

export function setActiveRow(state: EditorRowsState, activeRow: number): EditorRowsState {
  return state.activeRow === activeRow ? state : { ...state, activeRow };
}

/** The user typed into a row: its grid becomes a local estimate and it is no longer arranged. */
export function typeLine(state: EditorRowsState, row: number, text: string): EditorRowsState {
  const rows = toRows(state);
  if (row < 0 || row >= rows.length) return state;
  rows[row] = { text, grid: text.trim() ? localSyllabifyLine(text) : null, arranged: false };
  return fromRows(rows, state.activeRow);
}

/** Insert a blank row after `afterRow` and make it active. */
export function insertRow(state: EditorRowsState, afterRow: number): EditorRowsState {
  const rows = toRows(state);
  rows.splice(afterRow + 1, 0, { text: '', grid: null, arranged: false });
  return fromRows(rows, afterRow + 1);
}

/** Remove a row (keeping at least one) and activate the row above. */
export function deleteRow(state: EditorRowsState, row: number): EditorRowsState {
  const rows = toRows(state);
  if (row < 0 || row >= rows.length) return state;
  if (rows.length === 1) return fromRows([{ text: '', grid: null, arranged: false }], 0);
  rows.splice(row, 1);
  return fromRows(rows, Math.max(0, row - 1));
}

function updateGrid(state: EditorRowsState, row: number, fn: (line: GridLine) => GridLine): EditorRowsState {
  const rows = toRows(state);
  const current = rows[row];
  if (!current || !current.grid) return state;
  const next = fn(current.grid);
  if (next === current.grid) return state;
  const grid = withRebuiltText(next);
  rows[row] = { text: grid.raw_text, grid, arranged: true };
  return fromRows(rows, state.activeRow);
}

/** Move cells `[start, end)` of a row so the chunk starts at `to` (see `moveChunk`). */
export function moveChunkInRow(state: EditorRowsState, row: number, start: number, end: number, to: number): EditorRowsState {
  return updateGrid(state, row, (line) => moveChunk(line, start, end, to));
}

export function insertRestInRow(state: EditorRowsState, row: number, at: number): EditorRowsState {
  return updateGrid(state, row, (line) => insertRest(line, at));
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
  return fromRows(rows, activeRow);
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
  return fromRows(next, state.activeRow);
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
