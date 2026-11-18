/**
 * Staff editing operations: pure functions from a valid score (and its grid) to a valid score or a typed
 * refusal. Every successful result is re-validated against the grid, so the editor can never hold an
 * invalid score. Notes keep their absolute ticks under meter, tempo and pickup changes (bar lines move,
 * the sung rhythm does not).
 */
import { SCORE_MAX_BAR, SCORE_MAX_TICKS } from '@/types/score';
import type { Score, ScoreAccent, ScoreMeter, ScoreMeterDen, ScoreNote, ScoreSnap } from '@/types/score';
import { defaultAccent } from './draft';
import { gridLines, isRest, lineCells, syllableCount } from './gridView';
import type { CellJson, GridJson } from './gridView';
import { barAtTick, barStartTick, noteEnd, PICKUP_BAR } from './timing';
import { checkScore, isValidBpm, isValidMeterValue, MAX_METERS, MAX_TEMPOS, meterBarLength } from './validate';

export type EditRefusal =
  | 'no_syllable'
  | 'not_placed'
  | 'no_room'
  | 'first_bar_required'
  | 'bar_out_of_range'
  | 'too_many_changes'
  | 'invalid_meter'
  | 'invalid_tempo'
  | 'invalid_pickup';

export type EditResult = { readonly ok: true; readonly score: Score } | { readonly ok: false; readonly reason: EditRefusal };

const refuse = (reason: EditRefusal): EditResult => ({ ok: false, reason });

function done(score: Score, grid: GridJson): EditResult {
  return { ok: true, score: checkScore(score, grid) };
}

/** The free span `[lo, hi)` a note of syllable `s` of `line` may occupy (its own note excluded). */
export function noteWindow(score: Score, line: number, s: number): { lo: number; hi: number } {
  let lo = 0;
  let hi = SCORE_MAX_TICKS;
  for (let i = 0; i < score.lines.length; i++) {
    for (const note of score.lines[i].notes) {
      const before = i < line || (i === line && note.s < s);
      const after = i > line || (i === line && note.s > s);
      if (before) lo = noteEnd(note);
      else if (after) {
        hi = note.t;
        return { lo, hi };
      }
    }
  }
  return { lo, hi };
}

function syllableCell(grid: GridJson, line: number, s: number): CellJson | null {
  const lines = gridLines(grid);
  if (line < 0 || line >= lines.length) return null;
  let ordinal = 0;
  for (const cell of lineCells(lines[line])) {
    if (isRest(cell)) continue;
    if (ordinal === s) return cell;
    ordinal++;
  }
  return null;
}

export function findNote(score: Score, line: number, s: number): ScoreNote | null {
  return score.lines[line]?.notes.find((note) => note.s === s) ?? null;
}

function withLineNotes(score: Score, line: number, notes: ScoreNote[]): Score {
  return { ...score, lines: score.lines.map((l, i) => (i === line ? { notes } : l)) };
}

function replaceNote(score: Score, line: number, s: number, fn: (note: ScoreNote) => ScoreNote): Score {
  return withLineNotes(
    score,
    line,
    score.lines[line].notes.map((note) => (note.s === s ? fn(note) : note)),
  );
}

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

/**
 * Place syllable `s` of `line` at `t` for `d` ticks (moving it when already placed). The note is clamped
 * into the free window and shortened when the window is shorter than `d`.
 */
export function placeNote(score: Score, grid: GridJson, line: number, s: number, t: number, d: number): EditResult {
  const cell = syllableCell(grid, line, s);
  if (cell === null || line >= score.lines.length) return refuse('no_syllable');
  const { lo, hi } = noteWindow(score, line, s);
  if (hi - lo < 1) return refuse('no_room');
  const length = clamp(Math.round(d), 1, hi - lo);
  const start = clamp(Math.round(t), lo, hi - length);
  const existing = findNote(score, line, s);
  const note: ScoreNote = existing ? { ...existing, t: start, d: length } : { s, t: start, d: length, a: defaultAccent(cell.stress) };
  const others = score.lines[line].notes.filter((n) => n.s !== s);
  const notes = [...others, note].sort((x, y) => x.s - y.s);
  return done(withLineNotes(score, line, notes), grid);
}

/** Move a placed note's start to `t` (clamped into its window), keeping its duration. */
export function moveNote(score: Score, grid: GridJson, line: number, s: number, t: number): EditResult {
  const note = findNote(score, line, s);
  if (note === null) return refuse('not_placed');
  const { lo, hi } = noteWindow(score, line, s);
  const start = clamp(Math.round(t), lo, Math.max(lo, hi - note.d));
  return done(
    replaceNote(score, line, s, (n) => ({ ...n, t: start })),
    grid,
  );
}

/** Set a placed note's duration, between 1 and the start of the next note. */
export function resizeNote(score: Score, grid: GridJson, line: number, s: number, d: number): EditResult {
  const note = findNote(score, line, s);
  if (note === null) return refuse('not_placed');
  const { hi } = noteWindow(score, line, s);
  const length = clamp(Math.round(d), 1, hi - note.t);
  return done(
    replaceNote(score, line, s, (n) => ({ ...n, d: length })),
    grid,
  );
}

/** Accent 0 -> 1 -> 2 -> 0. */
export function cycleAccent(score: Score, grid: GridJson, line: number, s: number): EditResult {
  if (findNote(score, line, s) === null) return refuse('not_placed');
  return done(
    replaceNote(score, line, s, (n) => ({ ...n, a: ((n.a + 1) % 3) as ScoreAccent })),
    grid,
  );
}

/** Remove the note: the syllable goes back to the tray. */
export function unplaceNote(score: Score, grid: GridJson, line: number, s: number): EditResult {
  if (findNote(score, line, s) === null) return refuse('not_placed');
  return done(
    withLineNotes(
      score,
      line,
      score.lines[line].notes.filter((n) => n.s !== s),
    ),
    grid,
  );
}

function changeAt<E extends { bar: number }>(entries: readonly E[], bar: number, entry: E | null): E[] {
  const kept = entries.filter((e) => e.bar !== bar);
  return entry === null ? kept : [...kept, entry].sort((x, y) => x.bar - y.bar);
}

/** Add or replace (`meter`) or remove (`null`) the meter change at `bar`. */
export function setMeterAt(score: Score, grid: GridJson, bar: number, meter: { num: number; den: number } | null): EditResult {
  if (bar < 0 || bar > SCORE_MAX_BAR) return refuse('bar_out_of_range');
  if (meter === null && bar === 0) return refuse('first_bar_required');
  if (meter !== null && !(Number.isInteger(meter.num) && isValidMeterValue(meter.num, meter.den))) return refuse('invalid_meter');
  const entry: ScoreMeter | null = meter === null ? null : { bar, num: meter.num, den: meter.den as ScoreMeterDen };
  const meters = changeAt(score.meters, bar, entry);
  if (meters.length > MAX_METERS) return refuse('too_many_changes');
  const pickup = score.pickup < meterBarLength(meters[0]) ? score.pickup : 0;
  return done({ ...score, meters, pickup }, grid);
}

/** Add or replace (`bpm`) or remove (`null`) the tempo change at `bar`. */
export function setTempoAt(score: Score, grid: GridJson, bar: number, bpm: number | null): EditResult {
  if (bar < 0 || bar > SCORE_MAX_BAR) return refuse('bar_out_of_range');
  if (bpm === null && bar === 0) return refuse('first_bar_required');
  if (bpm !== null && !(Number.isFinite(bpm) && isValidBpm(bpm))) return refuse('invalid_tempo');
  const tempos = changeAt(score.tempos, bar, bpm === null ? null : { bar, bpm });
  if (tempos.length > MAX_TEMPOS) return refuse('too_many_changes');
  return done({ ...score, tempos }, grid);
}

/** Ticks before bar 0, `0 <= pickup < length of bar 0`. Notes keep their ticks. */
export function setPickup(score: Score, grid: GridJson, pickup: number): EditResult {
  if (!Number.isInteger(pickup) || pickup < 0 || pickup >= meterBarLength(score.meters[0])) return refuse('invalid_pickup');
  return done({ ...score, pickup }, grid);
}

export function setSnap(score: Score, snap: ScoreSnap): Score {
  return score.snap === snap ? score : { ...score, snap };
}

/** The nearest snap point to `tick`, counted from the start of the bar holding it (the pickup counts back from bar 0). */
export function snapTick(score: Score, tick: number, snap: number): number {
  const at = Math.max(0, tick);
  const position = barAtTick(score, at);
  const base = position.bar === PICKUP_BAR ? score.pickup : barStartTick(score, position.bar);
  return Math.max(0, base + Math.round((at - base) / snap) * snap);
}

/** Syllables of each line without a note, in order (the tray). */
export function unplacedSyllables(score: Score, grid: GridJson): number[][] {
  return gridLines(grid).map((line, i) => {
    const placed = new Set((score.lines[i]?.notes ?? []).map((n) => n.s));
    const out: number[] = [];
    const n = syllableCount(lineCells(line));
    for (let s = 0; s < n; s++) if (!placed.has(s)) out.push(s);
    return out;
  });
}
