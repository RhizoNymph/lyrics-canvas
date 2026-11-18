/**
 * Wire types of the score draft and export endpoints (round-4 contract §5) and the typed error every
 * call of `api/score.ts` rejects with. See docs/features/web_score_tools.md.
 */
import type { Score, ScoreInvalidReason, ScoreMeterDen, ScoreSnap } from './score';
import { SCORE_INVALID_REASONS } from './score';

/** A meter as the draft endpoint takes it (the score's meter without `bar`). */
export interface DraftMeter {
  num: number;
  den: ScoreMeterDen;
}

/** `POST /api/snippets/{id}/score/draft` from the snippet's stored grid. */
export interface GridDraftRequest {
  source: 'grid';
  note: ScoreSnap;
  meter: DraftMeter;
  bpm: number;
}

/** `POST /api/snippets/{id}/score/draft` from a voice take's word timings; `bpm` null = estimate it. */
export interface RecordingDraftRequest {
  source: 'recording';
  recording_id: string;
  meter: DraftMeter;
  bpm: number | null;
}

export type ScoreDraftRequest = GridDraftRequest | RecordingDraftRequest;

/** A draft: shown to the user, saved only through the snippet PUT. */
export interface ScoreDraftResponse {
  score: Score;
  /** The tempo estimated from the take's onsets; null when a bpm was sent (or for grid drafts). */
  bpm_estimated: number | null;
  /** Grid syllables left without a note. */
  unplaced: number;
  /** Take words that matched no grid word. */
  unmatched_words: number;
}

export type ScoreExportFormat = 'midi' | 'musicxml';

export const SCORE_EXPORT_FORMATS: readonly ScoreExportFormat[] = ['midi', 'musicxml'];

export const SCORE_EXPORT_LABELS: Readonly<Record<ScoreExportFormat, string>> = {
  midi: 'MIDI',
  musicxml: 'MusicXML',
};

/** File extension of each export format (the server's names end the same way). */
export const SCORE_EXPORT_EXTENSIONS: Readonly<Record<ScoreExportFormat, string>> = {
  midi: '.mid',
  musicxml: '.musicxml',
};

/** A downloaded export: the bytes and the file name to save them under. */
export interface ScoreExportDownload {
  blob: Blob;
  filename: string;
}

/** `detail.error` codes of the draft and export endpoints (contract §5, plus `invalid_score`). */
export const SCORE_SERVER_ERROR_CODES = [
  'snippet_not_found',
  'score_not_found',
  'recording_not_found',
  'recording_has_no_timings',
  'draft_empty',
  'invalid_score',
] as const;

export type ScoreServerErrorCode = (typeof SCORE_SERVER_ERROR_CODES)[number];

/**
 * Why a score API call failed:
 * - a server code above;
 * - `invalid_request`: 422 without a code (request validation, e.g. a bad meter or bpm);
 * - `not_found`: 404 without a code (an older backend without the routes);
 * - `server_unavailable`: 502/503/504 without a code;
 * - `network`: no response; `aborted`: cancelled by the client;
 * - `http`: any other failure.
 */
export type ScoreApiErrorKind =
  | ScoreServerErrorCode
  | 'invalid_request'
  | 'not_found'
  | 'server_unavailable'
  | 'network'
  | 'aborted'
  | 'http';

export type ScoreApiOperation = 'draft' | 'export';

/**
 * Typed failure of `api/score.ts`. `reason` / `line` / `note` are set only for `invalid_score` (the
 * server's `{error: "invalid_score", reason, line?, note?}`).
 */
export class ScoreApiError extends Error {
  readonly kind: ScoreApiErrorKind;
  readonly operation: ScoreApiOperation;
  /** HTTP status when the server answered; null otherwise. */
  readonly status: number | null;
  readonly reason: ScoreInvalidReason | null;
  readonly line: number | null;
  readonly note: number | null;

  constructor(
    kind: ScoreApiErrorKind,
    operation: ScoreApiOperation,
    message: string,
    status: number | null = null,
    invalid: { reason: ScoreInvalidReason; line: number | null; note: number | null } | null = null,
  ) {
    super(message);
    this.name = 'ScoreApiError';
    this.kind = kind;
    this.operation = operation;
    this.status = status;
    this.reason = invalid?.reason ?? null;
    this.line = invalid?.line ?? null;
    this.note = invalid?.note ?? null;
  }
}

export function isScoreServerErrorCode(value: unknown): value is ScoreServerErrorCode {
  return typeof value === 'string' && (SCORE_SERVER_ERROR_CODES as readonly string[]).includes(value);
}

export function isScoreInvalidReason(value: unknown): value is ScoreInvalidReason {
  return typeof value === 'string' && (SCORE_INVALID_REASONS as readonly string[]).includes(value);
}
