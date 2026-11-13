/**
 * TypeScript mirrors of the rhyme-pattern API (rhyme-pattern contract §1.2). Keep field names identical
 * to the pydantic models in backend/app/schemas/rhyme_pattern.py. See docs/features/web_rhyme_patterns.md.
 */
import type { GridLine } from './index';
import type { DialectMap, RhymeCandidate, RhymeK, RhymeSyllable, RhymeUnit, ScoreWeights, SourceName } from './rhyme';

/** At least 2 and at most 16 lines in a pattern range. */
export const PATTERN_MIN_LINES = 2;
export const PATTERN_MAX_LINES = 16;
/** At most 8 slots per suggest request. */
export const PATTERN_MAX_SLOTS = 8;
/** At most 16 members per slot (one per pattern line). */
export const PATTERN_MAX_MEMBERS = 16;
export const PATTERN_DETECT_MIN_SCORE = 0.75;
export const PATTERN_DETECT_MIN_SCORE_FLOOR = 0.3;
/** Per-slot page size (the server allows 1..100). */
export const PATTERN_PAGE_SIZE = 30;
export const PATTERN_LINE_MATCH_PAGE_SIZE = 30;

/** The selected range of one snippet: `[line_start, line_end)`. */
export interface PatternLines {
  canvas_id: string;
  snippet_id: string;
  line_start: number;
  /** Exclusive. */
  line_end: number;
  weights: ScoreWeights | null;
  dialect: DialectMap | null;
}

/** A word range `[word_start, word_end)` of one line (word indexes count words only, rests excluded). */
export interface SpanRef {
  /** Absolute line index in the snippet. */
  line_index: number;
  word_start: number;
  /** Exclusive. */
  word_end: number;
}

export interface PatternSpan extends SpanRef {
  text: string;
  syllable_count: number;
  stress: string;
  rhyme_key: string[];
  syllables: RhymeSyllable[];
}

export interface PatternSlot {
  /** "s0".. for detected slots; echoed for client-sent ones. */
  id: string;
  /** In line order. */
  members: PatternSpan[];
  coverage: number;
  full: boolean;
  default_on: boolean;
  k: RhymeK;
  cohesion: number;
  syllable_count: number;
  stress: string;
}

export interface PatternWord {
  word_index: number;
  text: string;
  syllable_count: number;
  stress: string;
}

export interface PatternLine {
  line_index: number;
  text: string;
  words: PatternWord[];
  grid_line: GridLine;
}

export interface PatternDetectRequest extends PatternLines {
  min_score: number;
}

export interface PatternDetectResponse {
  lines: PatternLine[];
  /** May be empty: the lines share no rhyme at that threshold. */
  slots: PatternSlot[];
  elapsed_ms: number;
}

export interface SlotSpec {
  id: string | null;
  /** 1..16, in line order, at most one per line. */
  members: SpanRef[];
  /** Null = derived by the server. */
  k: RhymeK | null;
}

export interface PatternSuggestRequest extends PatternLines {
  /** 1..8, in slot order. */
  slots: SlotSpec[];
  min_score: number;
  sources: SourceName[];
  include_rare: boolean;
  /** Normalized tag expression; omitted when empty, never sent while invalid. */
  tag_filter?: string;
  exclude_ids: string[];
  limit: number;
  /** Slot id -> offset. */
  offsets: Record<string, number>;
  line_matches: boolean;
  line_match_limit: number;
  line_match_offset: number;
}

export interface PatternCandidate extends RhymeCandidate {
  /** Score against each slot member, member order; `score` is their minimum. */
  member_scores: number[];
}

export interface PatternSlotResult {
  /** Recomputed from the members sent. */
  slot: PatternSlot;
  candidates: PatternCandidate[];
  total: number;
}

export interface LineMatchHit {
  slot_id: string;
  word_start: number;
  word_end: number;
  text: string;
  score: number;
}

export interface LineMatch {
  unit: RhymeUnit;
  /** In slot order and word order. */
  hits: LineMatchHit[];
  slots_hit: number;
  score: number;
}

export interface PatternSuggestResponse {
  slots: PatternSlotResult[];
  line_matches: LineMatch[];
  line_match_total: number;
  elapsed_ms: number;
}
