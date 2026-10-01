/**
 * TypeScript mirrors of the rhyme-suggest API contract.
 * See docs/features/rhyme_view.md ("API contract"). Keep field names identical to the
 * pydantic models in backend/app/schemas/rhyme.py.
 */
import type { GridLine } from './index';

export type Stress = 0 | 1 | 2;
export type RhymeK = 1 | 2 | 3 | 4;
export type Provenance = 'note' | 'dictionary' | 'datamuse' | 'generated';
export type SourceName = 'notes' | 'dictionary';

export const RHYME_K_VALUES: readonly RhymeK[] = [1, 2, 3, 4];
export const STRESS_PATTERN = /^[012]+$/;
export const RHYME_PAGE_SIZE = 40;
export const RHYME_MAX_LIMIT = 100;

export interface RhymeSyllable {
  onset: string[];
  nucleus: string;
  coda: string[];
  stress: Stress;
}

export interface RhymeUnit {
  id: string;
  text: string;
  provenance: Provenance;
  ephemeral: boolean;
  syllable_count: number;
  stress: string;
  rhyme_key: string[];
  syllables: RhymeSyllable[];
  /** Renders with the existing beat-grid cell code. */
  grid_line: GridLine;
  snippet_id?: string;
  line_index?: number;
  /** Note units: first word of the line included. Echoes `word_start` for a range anchor. */
  word_offset?: number;
  /** Range anchors only: one past the last word included. Null / absent for whole-line and suffix units. */
  word_end?: number | null;
  tags: string[];
}

export interface SyllableAlignment {
  cand_index: number | null;
  anchor_index: number | null;
  nucleus: number;
  coda: number;
  onset: number;
  total: number;
}

export interface RhymeCandidate extends RhymeUnit {
  score: number;
  match_depth: number;
  best_anchor_id: string;
  fits_slot: boolean;
  /** Right-to-left: index 0 is the final syllable. */
  alignment: SyllableAlignment[];
  source: SourceName;
}

/**
 * A snippet line (`line_index: null` = last non-empty line), optionally narrowed to the words
 * `[word_start, word_end)` of it. Ranges snap to word boundaries: the engine rhymes on words.
 */
export interface SnippetAnchorRef {
  kind: 'snippet';
  snippet_id: string;
  line_index: number | null;
  word_start?: number;
  /** Exclusive; omitted = to the end of the line. */
  word_end?: number;
}

export interface TextAnchorRef {
  kind: 'text';
  text: string;
}

export type AnchorRef = SnippetAnchorRef | TextAnchorRef;

/** The part of a selected snippet a rhyme query anchors on; `wordEnd` is exclusive. */
export interface AnchorRange {
  lineIndex: number;
  wordStart: number;
  wordEnd: number;
}

export interface Slot {
  syllable_count: number;
  /** Matches STRESS_PATTERN; a "0" marks a rest or unstressed beat. */
  stress: string;
}

export interface ScoreWeights {
  vowel: number;
  coda: number;
  onset: number;
}

export type DialectRuleKind = 'merge' | 'final_merge' | 'optional_final';

export interface DialectRule {
  kind: DialectRuleKind;
  source: string[];
  target: string[];
}

export interface DialectMap {
  rules: DialectRule[];
}

export interface DerivedFrom {
  provenance: Provenance;
  source_unit_id: string;
  score: number;
  match_depth: number;
  k: number;
  anchor_unit_id: string;
  anchor_text: string;
}

export interface RhymeSuggestRequest {
  canvas_id: string;
  anchors: AnchorRef[];
  context: AnchorRef[];
  slot: Slot | null;
  k: RhymeK;
  min_score: number;
  sources: SourceName[];
  include_rare: boolean;
  exclude_ids: string[];
  limit: number;
  offset: number;
  weights: ScoreWeights | null;
  dialect: DialectMap | null;
}

export interface RhymeSuggestResponse {
  anchors: RhymeUnit[];
  candidates: RhymeCandidate[];
  total: number;
  elapsed_ms: number;
}

// ---- Errors ----

export type RhymeErrorKind =
  | 'network'        // no response (offline, timeout, CORS)
  | 'not_found'      // 404: canvas not owned / missing
  | 'invalid_request' // 422: bad k, stress string, empty anchors
  | 'server'         // 5xx or any other HTTP failure
  | 'unknown';       // non-HTTP failure

export type RhymeOperation = 'query' | 'materialize';

/** Typed failure for rhyme-view API calls. Stored in rhymeStore.error; never thrown to callers. */
export class RhymeError extends Error {
  readonly kind: RhymeErrorKind;
  readonly operation: RhymeOperation;
  readonly status: number | null;

  constructor(kind: RhymeErrorKind, operation: RhymeOperation, message: string, status: number | null = null) {
    super(message);
    this.name = 'RhymeError';
    this.kind = kind;
    this.operation = operation;
    this.status = status;
  }
}
