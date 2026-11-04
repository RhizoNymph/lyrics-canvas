/**
 * Request bodies for the bank's rhyme views and the related-word search with its warming retry. The
 * filter sent is always the last valid one, normalized (`format`), and omitted when empty.
 */
import { cpLength, format } from '@/lib/tagExpr';
import type { FilterState } from '@/lib/tagFilter';
import type { BankFamiliesBody, BankRhymesBody, RelatedWordsResponse, RhymeAnchorInput } from '@/types/wordBank';
import { WordBankApiError, toWordBankApiError } from './errors';
import { MAX_WORD_LENGTH } from './text';

export const DEFAULT_FAMILY_MIN_SCORE = 0.8;
export const DEFAULT_RHYME_MIN_SCORE = 0.5;
export const MAX_RELATED_TRIES = 10;

export function tagFilterParam(filter: FilterState): string | undefined {
  return filter.applied === null ? undefined : format(filter.applied);
}

const clampScore = (s: number) => Math.min(1, Math.max(0, s));

export function familiesBody(filter: FilterState, minScore: number): BankFamiliesBody {
  const tag_filter = tagFilterParam(filter);
  return tag_filter === undefined ? { min_score: clampScore(minScore) } : { min_score: clampScore(minScore), tag_filter };
}

export function rhymesBody(anchor: RhymeAnchorInput, filter: FilterState, minScore: number): BankRhymesBody {
  const base: BankRhymesBody = anchor.kind === 'word' ? { word_id: anchor.wordId, min_score: clampScore(minScore) } : { text: anchor.text.trim(), min_score: clampScore(minScore) };
  const tag_filter = tagFilterParam(filter);
  return tag_filter === undefined ? base : { ...base, tag_filter };
}

export type RelatedQueryCheck = { ok: true; q: string } | { ok: false; problem: 'empty' | 'too_long' };

/** `q` is 1..64 code points after a strip (a phrase or a theme is fine). */
export function checkRelatedQuery(raw: string): RelatedQueryCheck {
  const q = raw.trim();
  if (q === '') return { ok: false, problem: 'empty' };
  if (cpLength(q) > MAX_WORD_LENGTH) return { ok: false, problem: 'too_long' };
  return { ok: true, q };
}

export interface RelatedSearchDeps {
  fetch: (q: string) => Promise<RelatedWordsResponse>;
  /** Resolves true after the delay, or false when the search was cancelled meanwhile. */
  sleep: (ms: number) => Promise<boolean>;
  /** Called before each wait, with the attempt that failed (1-based) and the delay in seconds. */
  onWarming: (attempt: number, retryAfterSeconds: number) => void;
}

export type RelatedSearchResult =
  | { ok: true; value: RelatedWordsResponse }
  | { ok: false; error: WordBankApiError | 'cancelled' };

/**
 * Search, retrying `related_words_warming` after its Retry-After, at most MAX_RELATED_TRIES requests in
 * all. Any other failure (`related_words_unavailable` included) ends the search at once.
 */
export async function searchRelated(q: string, deps: RelatedSearchDeps): Promise<RelatedSearchResult> {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return { ok: true, value: await deps.fetch(q) };
    } catch (err) {
      const error = toWordBankApiError(err, 'related');
      if (error.detail.kind !== 'related_words_warming' || attempt >= MAX_RELATED_TRIES) return { ok: false, error };
      deps.onWarming(attempt, error.detail.retryAfterSeconds);
      if (!(await deps.sleep(error.detail.retryAfterSeconds * 1000))) return { ok: false, error: 'cancelled' };
    }
  }
}

/**
 * "Rhymes with": a typed text wins; else exactly one selected word; else the previous word anchor
 * (`pinned`) while it is still selected, so selecting matches into the selection keeps the anchor.
 */
export function resolveRhymeAnchor(typed: string, selected: ReadonlySet<string>, pinned: string | null = null): RhymeAnchorInput | null {
  if (typed.trim() !== '') return { kind: 'text', text: typed.trim() };
  if (selected.size === 1) return { kind: 'word', wordId: [...selected][0] };
  if (pinned !== null && selected.has(pinned)) return { kind: 'word', wordId: pinned };
  return null;
}
