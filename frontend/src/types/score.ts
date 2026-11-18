/**
 * Snippet score ("musical mode") wire types: score format v1 (spec/score_format.md).
 *
 * Types only. The algorithms (validate, timing, rest link, reconcile, draft) are ported in
 * frontend/src/lib/score/ against spec/score_vectors.json.
 */

/** Ticks per quarter note (fixed in v1). */
export const SCORE_PPQ = 48;
/** Ticks per whole note. */
export const SCORE_WHOLE = 192;
/** Highest bar a meter or tempo change may sit at. */
export const SCORE_MAX_BAR = 2048;
/** No note may end after this tick (48 * 4 * 2048). */
export const SCORE_MAX_TICKS = 393216;

/** The editor's snap choice; also the note length of a grid draft. */
export type ScoreSnap = '1/8' | '1/16' | '1/32' | '1/8t' | '1/16t';

export const SCORE_SNAPS: readonly ScoreSnap[] = ['1/8', '1/16', '1/32', '1/8t', '1/16t'];

/** Length of each snap value in ticks. */
export const SCORE_SNAP_TICKS: Readonly<Record<ScoreSnap, number>> = {
  '1/8': 24,
  '1/16': 12,
  '1/32': 6,
  '1/8t': 16,
  '1/16t': 8,
};

/** Allowed meter denominators. */
export type ScoreMeterDen = 1 | 2 | 4 | 8 | 16 | 32;

/** 0 none, 1 accent, 2 strong. */
export type ScoreAccent = 0 | 1 | 2;

/** A meter change: holds from `bar` until the next entry. The first entry is at bar 0. */
export interface ScoreMeter {
  bar: number;
  /** 1..32 */
  num: number;
  den: ScoreMeterDen;
}

/** A tempo change at a bar start. `bpm` is quarter notes per minute, 20..400, at most 2 decimals. */
export interface ScoreTempo {
  bar: number;
  bpm: number;
}

/**
 * One placed syllable. `s` is the syllable ordinal in the grid line (rest cells not counted), `t` the
 * start tick from tick 0, `d` the duration in ticks (>= 1). Unknown keys (a future pitch) are kept by
 * every algorithm; read them through the index signature only.
 */
export interface ScoreNote {
  s: number;
  t: number;
  d: number;
  a: ScoreAccent;
  [extra: string]: unknown;
}

/** One entry per grid line (same index), rest-only lines included. */
export interface ScoreLine {
  notes: ScoreNote[];
}

/** Score format v1. */
export interface Score {
  v: 1;
  ppq: 48;
  /** Ticks before bar 0 (0 <= pickup < length of bar 0); tick 0 is the start of the pickup. */
  pickup: number;
  meters: ScoreMeter[];
  tempos: ScoreTempo[];
  snap: ScoreSnap;
  lines: ScoreLine[];
}

/**
 * The snippet content fields of musical mode. The store branch adds them to `Snippet` (and to the
 * create/update bodies: `score` may be sent as an object or as explicit `null` = Remove score).
 * `score_view` is only meaningful when `score` is non-null; the server forces false otherwise.
 */
export interface SnippetScoreFields {
  score: Score | null;
  score_view: boolean;
}

/** Every `reason` of the `invalid_score` error, in the order validate checks them. */
export type ScoreInvalidReason =
  | 'malformed'
  | 'unsupported_version'
  | 'bad_ppq'
  | 'meters_empty'
  | 'too_many_meters'
  | 'meters_first_not_zero'
  | 'meters_not_increasing'
  | 'bar_out_of_range'
  | 'meter_invalid'
  | 'tempos_empty'
  | 'too_many_tempos'
  | 'tempos_first_not_zero'
  | 'tempos_not_increasing'
  | 'tempo_invalid'
  | 'bad_pickup'
  | 'snap_invalid'
  | 'line_count_mismatch'
  | 'syllable_out_of_range'
  | 'syllables_not_increasing'
  | 'start_negative'
  | 'duration_invalid'
  | 'accent_invalid'
  | 'exceeds_max_ticks'
  | 'notes_overlap';

export const SCORE_INVALID_REASONS: readonly ScoreInvalidReason[] = [
  'malformed',
  'unsupported_version',
  'bad_ppq',
  'meters_empty',
  'too_many_meters',
  'meters_first_not_zero',
  'meters_not_increasing',
  'bar_out_of_range',
  'meter_invalid',
  'tempos_empty',
  'too_many_tempos',
  'tempos_first_not_zero',
  'tempos_not_increasing',
  'tempo_invalid',
  'bad_pickup',
  'snap_invalid',
  'line_count_mismatch',
  'syllable_out_of_range',
  'syllables_not_increasing',
  'start_negative',
  'duration_invalid',
  'accent_invalid',
  'exceeds_max_ticks',
  'notes_overlap',
];

/**
 * The typed `invalid_score` error (422), as the `detail` object of the response like every other
 * typed error of the API. `line` / `note` are 0-based, present for line and note failures.
 */
export interface ScoreInvalidBody {
  error: 'invalid_score';
  reason: ScoreInvalidReason;
  line?: number;
  note?: number;
}
