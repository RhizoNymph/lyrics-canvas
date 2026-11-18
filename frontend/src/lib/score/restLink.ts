/**
 * The rest link in both directions (spec/score_format.md sections 8 and 9). Score -> grid: gaps between
 * consecutive placed syllables become rest cells. Grid -> score: a grid edit that only changed rest cells
 * moves the notes, then the score -> grid direction makes the grid canonical.
 */
import type { Score, ScoreLine, ScoreNote } from '@/types/score';
import { NotRestOnlyEdit } from './errors';
import { jsonEqual } from './json';
import { gridLines, lineCells, rebuildLineText, restsBetween, syllableIdentity, syllablePositions } from './gridView';
import type { CellJson, GridJson, LineJson } from './gridView';
import { beatUnit, noteEnd } from './timing';
import { checkScore } from './validate';

export function notesBySyllable(line: ScoreLine): Map<number, ScoreNote> {
  return new Map(line.notes.map((note) => [note.s, note]));
}

function linkedCells(score: Score, cells: readonly CellJson[], line: ScoreLine): CellJson[] {
  const syl = syllablePositions(cells);
  if (syl.length === 0) return [...cells];
  const notes = notesBySyllable(line);
  const out: CellJson[] = cells.slice(0, syl[0] + 1);
  for (let k = 0; k + 1 < syl.length; k++) {
    const run = cells.slice(syl[k] + 1, syl[k + 1]);
    const left = notes.get(k);
    const right = notes.get(k + 1);
    if (left !== undefined && right !== undefined) {
      const end = noteEnd(left);
      const target = Math.floor((right.t - end) / beatUnit(score, end));
      out.push(...run.slice(0, target));
      for (let r = run.length; r < target; r++) out.push({ rest: true });
    } else {
      out.push(...run);
    }
    out.push(cells[syl[k + 1]]);
  }
  out.push(...cells.slice(syl[syl.length - 1] + 1));
  return out;
}

/**
 * The grid with the rest count between every two consecutive placed syllables set from the score.
 * Lines whose cells do not change are returned as they are (same object, `raw_text` untouched); a changed
 * line gets the new cells and its rebuilt `raw_text`. Throws `ScoreInvalid` when `score` does not fit.
 */
export function applyRestLink<G extends GridJson>(score: Score, grid: G): G {
  checkScore(score, grid);
  if (grid === null) return grid;
  let changed = false;
  const lines = gridLines(grid).map((line, i): LineJson => {
    const cells = lineCells(line);
    const linked = linkedCells(score, cells, score.lines[i]);
    if (jsonEqual(linked, cells)) return line;
    changed = true;
    return { ...line, cells: linked, raw_text: rebuildLineText(linked) };
  });
  return (changed ? { ...grid, lines } : { ...grid, lines: gridLines(grid) }) as G;
}

/** Same lines with the same syllables (`syllable` and `word_index`); only rests differ. */
export function isRestOnlyEdit(oldGrid: GridJson, newGrid: GridJson): boolean {
  const oldLines = gridLines(oldGrid);
  const newLines = gridLines(newGrid);
  if (oldLines.length !== newLines.length) return false;
  return oldLines.every((line, i) => jsonEqual(syllableIdentity(lineCells(line)), syllableIdentity(lineCells(newLines[i]))));
}

export interface GridEditResult<G extends GridJson> {
  readonly score: Score;
  readonly grid: G;
}

function shift(notes: readonly ScoreNote[], delta: number, fromS = 0): ScoreNote[] {
  if (delta === 0) return [...notes];
  return notes.map((note) => (note.s >= fromS ? { ...note, t: note.t + delta } : note));
}

/**
 * Move the notes for a rest-only grid edit, then make the grid canonical (spec section 9). Throws
 * `ScoreInvalid` when `score` does not fit `oldGrid` or a moved note would pass MAX_TICKS, and
 * `NotRestOnlyEdit` when the edit changed syllables.
 */
export function applyGridRestEdit<G extends GridJson>(score: Score, oldGrid: GridJson, newGrid: G): GridEditResult<G> {
  checkScore(score, oldGrid);
  if (!isRestOnlyEdit(oldGrid, newGrid)) throw new NotRestOnlyEdit('the grid edit changed syllables or lines, not only rests');
  const oldLines = gridLines(oldGrid);
  const newLines = gridLines(newGrid);
  let prevEnd = 0;
  const moved = score.lines.map((line, i): ScoreLine => {
    if (line.notes.length === 0) return line;
    let notes = shift(line.notes, Math.max(0, prevEnd - line.notes[0].t));
    const oldR = restsBetween(lineCells(oldLines[i]));
    const newR = restsBetween(lineCells(newLines[i]));
    for (let k = 0; k < oldR.length; k++) {
      const bySyllable = new Map(notes.map((note) => [note.s, note]));
      const left = bySyllable.get(k);
      const right = bySyllable.get(k + 1);
      if (left === undefined || right === undefined) continue;
      const delta = newR[k] - oldR[k];
      if (delta === 0) continue;
      const end = noteEnd(left);
      let amount = delta * beatUnit(score, end);
      if (amount < 0) amount = -Math.min(-amount, right.t - end);
      notes = shift(notes, amount, k + 1);
    }
    prevEnd = noteEnd(notes[notes.length - 1]);
    return { notes };
  });
  const movedScore = checkScore({ ...score, lines: moved }, newGrid);
  return { score: movedScore, grid: applyRestLink(movedScore, newGrid) };
}
