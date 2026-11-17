/** draft_from_grid: a first score laid out cell by cell (spec/score_format.md section 12). */
import { SCORE_SNAP_TICKS, SCORE_WHOLE } from '@/types/score';
import type { Score, ScoreAccent, ScoreLine, ScoreNote } from '@/types/score';
import { ScoreInvalid } from './errors';
import { asInteger, asNumber, isJsonObject } from './json';
import { gridLines, isRest, lineCells } from './gridView';
import type { GridJson } from './gridView';
import { checkScore, isValidBpm, isValidMeterValue, meterBarLength, parseSnap } from './validate';

/** Grid stress 1 -> accent 1, anything else (0, 2, missing) -> 0. */
export function defaultAccent(stress: unknown): ScoreAccent {
  return asInteger(stress) === 1 ? 1 : 0;
}

/**
 * Each syllable takes one `note` length, each rest one beat unit, each line starts on a new bar. Throws
 * `ScoreInvalid` (meter_invalid, tempo_invalid, snap_invalid, exceeds_max_ticks). Arguments are read as
 * raw JSON values.
 */
export function draftFromGrid(grid: GridJson, meter: unknown, bpm: unknown, note: unknown): Score {
  const num = isJsonObject(meter) ? asInteger(meter.num) : null;
  const den = isJsonObject(meter) ? asInteger(meter.den) : null;
  if (num === null || den === null || !isValidMeterValue(num, den)) {
    throw new ScoreInvalid('meter_invalid', 'the meter is not a valid {num, den}');
  }
  const tempo = asNumber(bpm);
  if (tempo === null || !isValidBpm(tempo)) throw new ScoreInvalid('tempo_invalid', `bpm ${String(bpm)} is not valid`);
  const snap = parseSnap(note);
  const length = SCORE_SNAP_TICKS[snap];
  const beat = SCORE_WHOLE / den;
  const bar = meterBarLength({ num, den });
  let cursor = 0;
  const lines = gridLines(grid).map((line, index): ScoreLine => {
    if (index > 0) cursor = Math.ceil(cursor / bar) * bar;
    const notes: ScoreNote[] = [];
    let ordinal = 0;
    for (const cell of lineCells(line)) {
      if (isRest(cell)) {
        cursor += beat;
        continue;
      }
      notes.push({ s: ordinal, t: cursor, d: length, a: defaultAccent(cell.stress) });
      ordinal++;
      cursor += length;
    }
    return { notes };
  });
  const score: Score = { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num, den }], tempos: [{ bar: 0, bpm: tempo }], snap, lines };
  return checkScore(score, grid);
}
