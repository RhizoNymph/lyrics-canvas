/**
 * validate: a raw JSON value -> a canonical typed `Score`, or the first failure (spec/score_format.md
 * section 4). The order of the checks is normative: every port reports the same first failure.
 */
import { SCORE_MAX_BAR, SCORE_MAX_TICKS, SCORE_PPQ, SCORE_SNAPS, SCORE_WHOLE } from '@/types/score';
import type {
  Score,
  ScoreAccent,
  ScoreInvalidReason,
  ScoreLine,
  ScoreMeter,
  ScoreMeterDen,
  ScoreNote,
  ScoreSnap,
  ScoreTempo,
} from '@/types/score';
import { ScoreInvalid } from './errors';
import { asInteger, asNumber, hasExactKeys, isJsonObject } from './json';
import type { JsonObject } from './json';
import { syllableCounts } from './gridView';
import type { GridJson } from './gridView';

export const SCORE_VERSION = 1;
export const MAX_METERS = 256;
export const MAX_TEMPOS = 256;
export const NUM_MIN = 1;
export const NUM_MAX = 32;
export const DENS: readonly ScoreMeterDen[] = [1, 2, 4, 8, 16, 32];
export const BPM_MIN = 20;
export const BPM_MAX = 400;
const BPM_DECIMALS_TOLERANCE = 1e-6;

const SCORE_KEYS = ['v', 'ppq', 'pickup', 'meters', 'tempos', 'snap', 'lines'] as const;
const METER_KEYS = ['bar', 'num', 'den'] as const;
const TEMPO_KEYS = ['bar', 'bpm'] as const;
const LINE_KEYS = ['notes'] as const;
const NOTE_KEYS = ['s', 't', 'd', 'a'] as const;

export function isValidMeterValue(num: number, den: number): den is ScoreMeterDen {
  return num >= NUM_MIN && num <= NUM_MAX && (DENS as readonly number[]).includes(den);
}

/** 20..400 with at most two decimals (`|bpm*100 - round(bpm*100)| <= 1e-6`). */
export function isValidBpm(bpm: number): boolean {
  if (bpm < BPM_MIN || bpm > BPM_MAX) return false;
  const scaled = bpm * 100;
  return Math.abs(scaled - Math.round(scaled)) <= BPM_DECIMALS_TOLERANCE;
}

export function meterBarLength(meter: Pick<ScoreMeter, 'num' | 'den'>): number {
  return (meter.num * SCORE_WHOLE) / meter.den;
}

export function parseSnap(value: unknown): ScoreSnap {
  if (typeof value === 'string') {
    const found = SCORE_SNAPS.find((snap) => snap === value);
    if (found !== undefined) return found;
  }
  throw new ScoreInvalid('snap_invalid', `snap must be one of ${SCORE_SNAPS.join(', ')}`);
}

function checkBar(bar: number, index: number, previous: number | null, first: ScoreInvalidReason, order: ScoreInvalidReason, what: string): void {
  if (index === 0 && bar !== 0) throw new ScoreInvalid(first, `the first ${what} must be at bar 0, got ${bar}`);
  if (previous !== null && bar <= previous) throw new ScoreInvalid(order, `${what} ${index} at bar ${bar} is not after bar ${previous}`);
  if (bar > SCORE_MAX_BAR) throw new ScoreInvalid('bar_out_of_range', `${what} ${index} at bar ${bar} is past bar ${SCORE_MAX_BAR}`);
}

function integers(entry: JsonObject, keys: readonly string[]): number[] | null {
  const out: number[] = [];
  for (const key of keys) {
    const n = asInteger(entry[key]);
    if (n === null) return null;
    out.push(n);
  }
  return out;
}

function parseMeters(value: unknown): ScoreMeter[] {
  if (!Array.isArray(value)) throw new ScoreInvalid('malformed', 'meters must be an array');
  if (value.length === 0) throw new ScoreInvalid('meters_empty', 'meters must have an entry at bar 0');
  if (value.length > MAX_METERS) throw new ScoreInvalid('too_many_meters', `at most ${MAX_METERS} meters, got ${value.length}`);
  const meters: ScoreMeter[] = [];
  value.forEach((raw: unknown, index) => {
    const numbers = isJsonObject(raw) && hasExactKeys(raw, METER_KEYS) ? integers(raw, METER_KEYS) : null;
    if (numbers === null) throw new ScoreInvalid('malformed', `meter ${index} must be {bar, num, den} integers`);
    const [bar, num, den] = numbers;
    checkBar(bar, index, meters.length > 0 ? meters[meters.length - 1].bar : null, 'meters_first_not_zero', 'meters_not_increasing', 'meter');
    if (!isValidMeterValue(num, den)) throw new ScoreInvalid('meter_invalid', `meter ${index} is ${num}/${den}`);
    meters.push({ bar, num, den });
  });
  return meters;
}

function parseTempos(value: unknown): ScoreTempo[] {
  if (!Array.isArray(value)) throw new ScoreInvalid('malformed', 'tempos must be an array');
  if (value.length === 0) throw new ScoreInvalid('tempos_empty', 'tempos must have an entry at bar 0');
  if (value.length > MAX_TEMPOS) throw new ScoreInvalid('too_many_tempos', `at most ${MAX_TEMPOS} tempos, got ${value.length}`);
  const tempos: ScoreTempo[] = [];
  value.forEach((raw: unknown, index) => {
    const entry = isJsonObject(raw) && hasExactKeys(raw, TEMPO_KEYS) ? raw : null;
    const bar = entry === null ? null : asInteger(entry.bar);
    const bpm = entry === null ? null : asNumber(entry.bpm);
    if (bar === null || bpm === null) throw new ScoreInvalid('malformed', `tempo ${index} must be {bar: integer, bpm: number}`);
    checkBar(bar, index, tempos.length > 0 ? tempos[tempos.length - 1].bar : null, 'tempos_first_not_zero', 'tempos_not_increasing', 'tempo');
    if (!isValidBpm(bpm)) throw new ScoreInvalid('tempo_invalid', `tempo ${index} bpm ${bpm} is not ${BPM_MIN}..${BPM_MAX} with 2 decimals`);
    tempos.push({ bar, bpm });
  });
  return tempos;
}

function parseNote(raw: unknown, line: number, index: number): ScoreNote {
  if (!isJsonObject(raw) || NOTE_KEYS.some((key) => !Object.prototype.hasOwnProperty.call(raw, key))) {
    throw new ScoreInvalid('malformed', 'a note must be an object with s, t, d, a', line, index);
  }
  const numbers = integers(raw, NOTE_KEYS);
  if (numbers === null) throw new ScoreInvalid('malformed', 's, t, d, a must be integers', line, index);
  const [s, t, d, a] = numbers;
  const note: ScoreNote = { s, t, d, a: a as ScoreAccent };
  for (const key of Object.keys(raw)) {
    if (!(NOTE_KEYS as readonly string[]).includes(key)) note[key] = raw[key];
  }
  return note;
}

function parseLines(value: unknown, counts: readonly number[]): ScoreLine[] {
  if (!Array.isArray(value)) throw new ScoreInvalid('malformed', 'lines must be an array');
  if (value.length !== counts.length) {
    throw new ScoreInvalid('line_count_mismatch', `the score has ${value.length} lines, the grid ${counts.length}`);
  }
  let prevEnd = 0;
  return value.map((rawLine: unknown, li): ScoreLine => {
    if (!isJsonObject(rawLine) || !hasExactKeys(rawLine, LINE_KEYS) || !Array.isArray(rawLine.notes)) {
      throw new ScoreInvalid('malformed', 'a line must be {notes: [...]}', li);
    }
    const notes: ScoreNote[] = [];
    (rawLine.notes as unknown[]).forEach((rawNote, ni) => {
      const note = parseNote(rawNote, li, ni);
      if (note.s < 0 || note.s >= counts[li]) throw new ScoreInvalid('syllable_out_of_range', `s ${note.s} not in 0..${counts[li] - 1}`, li, ni);
      if (notes.length > 0 && note.s <= notes[notes.length - 1].s) {
        throw new ScoreInvalid('syllables_not_increasing', `s ${note.s} after s ${notes[notes.length - 1].s}`, li, ni);
      }
      if (note.t < 0) throw new ScoreInvalid('start_negative', `t ${note.t} < 0`, li, ni);
      if (note.d < 1) throw new ScoreInvalid('duration_invalid', `d ${note.d} < 1`, li, ni);
      if (note.a !== 0 && note.a !== 1 && note.a !== 2) throw new ScoreInvalid('accent_invalid', `a ${note.a} not 0, 1 or 2`, li, ni);
      const end = note.t + note.d;
      if (end > SCORE_MAX_TICKS) throw new ScoreInvalid('exceeds_max_ticks', `t + d = ${end} > ${SCORE_MAX_TICKS}`, li, ni);
      if (note.t < prevEnd) throw new ScoreInvalid('notes_overlap', `t ${note.t} starts before the previous note ends at ${prevEnd}`, li, ni);
      prevEnd = end;
      notes.push(note);
    });
    return { notes };
  });
}

/** The canonical score `raw` denotes when it is valid against `grid`; throws `ScoreInvalid`. */
export function checkScore(raw: unknown, grid: GridJson | undefined): Score {
  if (!isJsonObject(raw) || !hasExactKeys(raw, SCORE_KEYS)) {
    throw new ScoreInvalid('malformed', `a score is an object with exactly ${SCORE_KEYS.join(', ')}`);
  }
  const version = asInteger(raw.v);
  if (version === null) throw new ScoreInvalid('malformed', 'v must be an integer');
  if (version !== SCORE_VERSION) throw new ScoreInvalid('unsupported_version', `v ${version} is not ${SCORE_VERSION}`);
  const ppq = asInteger(raw.ppq);
  if (ppq === null) throw new ScoreInvalid('malformed', 'ppq must be an integer');
  if (ppq !== SCORE_PPQ) throw new ScoreInvalid('bad_ppq', `ppq ${ppq} is not ${SCORE_PPQ}`);
  const meters = parseMeters(raw.meters);
  const tempos = parseTempos(raw.tempos);
  const pickup = asInteger(raw.pickup);
  if (pickup === null) throw new ScoreInvalid('malformed', 'pickup must be an integer');
  const first = meterBarLength(meters[0]);
  if (pickup < 0 || pickup >= first) throw new ScoreInvalid('bad_pickup', `pickup ${pickup} not in 0..${first - 1}`);
  const snap = parseSnap(raw.snap);
  const lines = parseLines(raw.lines, syllableCounts(grid));
  return { v: 1, ppq: 48, pickup, meters, tempos, snap, lines };
}

export type ValidateResult = { readonly ok: true; readonly score: Score } | { readonly ok: false; readonly error: ScoreInvalid };

export function validateScore(raw: unknown, grid: GridJson | undefined): ValidateResult {
  try {
    return { ok: true, score: checkScore(raw, grid) };
  } catch (error) {
    if (error instanceof ScoreInvalid) return { ok: false, error };
    throw error;
  }
}
