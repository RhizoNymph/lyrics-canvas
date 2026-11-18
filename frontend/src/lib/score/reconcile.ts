/** reconcile: keep the notes of syllables that survive a text or grid change (spec section 11). */
import type { Score, ScoreLine, ScoreNote } from '@/types/score';
import { gridLines, lineCells, wordGroups } from './gridView';
import type { CellJson, GridJson } from './gridView';
import { lcsPairs, lineKey, wordKey } from './normalize';
import { notesBySyllable } from './restLink';
import { noteEnd } from './timing';
import { checkScore } from './validate';

/** new line index -> old line index. */
function linePairs(oldGrid: GridJson, newGrid: GridJson): Map<number, number> {
  const oldLines = gridLines(oldGrid);
  const newLines = gridLines(newGrid);
  if (oldLines.length === newLines.length) return new Map(newLines.map((_, i) => [i, i]));
  const pairs = lcsPairs(oldLines.map(lineKey), newLines.map(lineKey));
  return new Map(pairs.map(([o, n]) => [n, o]));
}

function mappedNotes(oldCells: readonly CellJson[], newCells: readonly CellJson[], oldLine: ScoreLine): ScoreNote[] {
  const oldGroups = wordGroups(oldCells);
  const newGroups = wordGroups(newCells);
  const pairs = lcsPairs(
    oldGroups.map((g) => wordKey(g.spelling)),
    newGroups.map((g) => wordKey(g.spelling)),
  );
  const bySyllable = notesBySyllable(oldLine);
  const candidates: ScoreNote[] = [];
  for (const [oi, ni] of pairs) {
    const oldOrdinals = oldGroups[oi].ordinals;
    const newOrdinals = newGroups[ni].ordinals;
    for (let r = 0; r < Math.min(oldOrdinals.length, newOrdinals.length); r++) {
      const note = bySyllable.get(oldOrdinals[r]);
      if (note !== undefined) candidates.push(note.s === newOrdinals[r] ? note : { ...note, s: newOrdinals[r] });
    }
  }
  return candidates.sort((x, y) => x.s - y.s);
}

/**
 * The score for `newGrid`: matched words keep their syllables' notes, the rest is unplaced. Throws
 * `ScoreInvalid` when `score` does not fit `oldGrid`.
 */
export function reconcile(score: Score, oldGrid: GridJson, newGrid: GridJson): Score {
  checkScore(score, oldGrid);
  const oldLines = gridLines(oldGrid);
  const pairs = linePairs(oldGrid, newGrid);
  let prevEnd = 0;
  const lines = gridLines(newGrid).map((newLine, ni): ScoreLine => {
    const oi = pairs.get(ni);
    if (oi === undefined) return { notes: [] };
    const kept: ScoreNote[] = [];
    for (const note of mappedNotes(lineCells(oldLines[oi]), lineCells(newLine), score.lines[oi])) {
      if (note.t >= prevEnd) {
        kept.push(note);
        prevEnd = noteEnd(note);
      }
    }
    return { notes: kept };
  });
  return checkScore({ ...score, lines }, newGrid);
}
