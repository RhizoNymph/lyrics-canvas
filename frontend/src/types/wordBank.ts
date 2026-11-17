/**
 * Wire shapes of the word bank API (docs/features/word_bank.md, contract §3). Field names match the
 * backend's pydantic models.
 */
import type { SnippetTag } from './index';
import type { RhymeBand, RhymeOrder } from '@/lib/rhyme/order';

/** A word or short phrase in the user's bank. `tags` are sorted by lower(name). */
export interface BankWord {
  id: string;
  text: string;
  tags: SnippetTag[];
  created_at: string;
}

/** `POST /api/word-bank` body: 1..200 words, 0..20 tag ids linked to every created and existing word. */
export interface BankWordCreateBody {
  words: string[];
  tag_ids: string[];
}

/** Both lists keep the input order; `existing` carries the stored spelling. */
export interface BankWordCreateResponse {
  created: BankWord[];
  existing: BankWord[];
}

export type WordTagOp = 'link' | 'unlink';

export interface WordTagsBody {
  word_ids: string[];
  tag_id: string;
  op: WordTagOp;
}

export interface WordTagsResponse {
  words: BankWord[];
}

/** The anchor of a "rhymes with" query: exactly one of a bank word id or a typed text. */
export type RhymeAnchorInput = { kind: 'word'; wordId: string } | { kind: 'text'; text: string };

export interface BankRhymesBody {
  word_id?: string;
  text?: string;
  tag_filter?: string;
  min_score: number;
  k?: number;
  limit?: number;
  /** "meaning" = rhyme bands, then similarity to the anchor word. Omitted for the server default "rhyme". */
  order?: 'meaning';
}

export interface BankRhymeAnchor {
  text: string;
  rhyme_key: string[];
  syllable_count: number;
}

export interface BankRhymeMatch {
  word: BankWord;
  score: number;
  match_depth: number;
  /** Always sent by the server; optional so older fixtures type-check (`rowBand` derives it from `score`). */
  band?: RhymeBand;
  /** Similarity to the anchor when meaning order was applied; null otherwise. */
  similarity?: number | null;
}

/** Matches sorted by score desc, match_depth desc, lower(text); in meaning order by band, then similarity. */
export interface BankRhymesResponse {
  anchor: BankRhymeAnchor;
  matches: BankRhymeMatch[];
  order_applied?: RhymeOrder;
}

export interface BankFamiliesBody {
  tag_filter?: string;
  min_score: number;
  k?: number;
}

/** `words` start with the medoid (`label`). A family has at least 2 words. */
export interface RhymeFamily {
  label: string;
  rhyme_key: string[];
  words: BankWord[];
  cohesion: number;
}

export interface BankFamiliesResponse {
  families: RhymeFamily[];
  unmatched: BankWord[];
}

export interface RelatedWord {
  text: string;
  similarity: number;
  /** Set when the user already has the word. */
  bank_word_id: string | null;
}

export interface RelatedWordsResponse {
  query: string;
  results: RelatedWord[];
}
