/**
 * Typed failures of the word bank endpoints (contract §3). Every new server code is a member of
 * `WordBankServerErrorCode`; the reused tag codes (`tag_not_found`, `invalid_tag_expression`, ...) keep
 * their tag kinds; anything else maps to a fallback kind. Code-specific data lives in `detail`, a
 * discriminated union, so an index exists only on `invalid_word` and a delay only on the warming state.
 */
import axios from 'axios';
import type { TagExprError } from '@/lib/tagExpr';
import { isTagServerErrorCode, parseServerDetail } from '@/lib/tags';
import type { TagServerErrorCode } from '@/lib/tags';

export const WORD_BANK_SERVER_ERROR_CODES = [
  'invalid_word',
  'word_exists',
  'word_not_found',
  'too_many_words',
  'related_words_warming',
  'related_words_unavailable',
] as const;

export type WordBankServerErrorCode = (typeof WORD_BANK_SERVER_ERROR_CODES)[number];

/** Same fallbacks as TagApiErrorKind: 404/422/5xx without a contract code, no response, anything else. */
export type WordBankFallbackKind = 'not_found' | 'validation' | 'network' | 'server' | 'http';

export type WordBankErrorKind = WordBankServerErrorCode | TagServerErrorCode | WordBankFallbackKind;

export type WordBankErrorDetail =
  /** `index`: the first bad position of a batch; null for a single text (rename, related query). */
  | { readonly kind: 'invalid_word'; readonly index: number | null }
  | { readonly kind: 'related_words_warming'; readonly retryAfterSeconds: number }
  | { readonly kind: 'invalid_tag_expression'; readonly expressionError: TagExprError | null }
  | { readonly kind: Exclude<WordBankErrorKind, 'invalid_word' | 'related_words_warming' | 'invalid_tag_expression'> };

export type WordBankOperation =
  | 'load_words'
  | 'add_words'
  | 'rename_word'
  | 'delete_words'
  | 'link_words'
  | 'unlink_words'
  | 'rhymes'
  | 'families'
  | 'related'
  | 'make_snippet';

export function isWordBankServerErrorCode(value: unknown): value is WordBankServerErrorCode {
  return typeof value === 'string' && (WORD_BANK_SERVER_ERROR_CODES as readonly string[]).includes(value);
}

export class WordBankApiError extends Error {
  readonly detail: WordBankErrorDetail;
  readonly operation: WordBankOperation;
  readonly status: number | null;

  constructor(detail: WordBankErrorDetail, operation: WordBankOperation, message: string, status: number | null = null) {
    super(message);
    this.name = 'WordBankApiError';
    this.detail = detail;
    this.operation = operation;
    this.status = status;
  }

  get kind(): WordBankErrorKind {
    return this.detail.kind;
  }
}

export type WordBankResult<T> = { ok: true; value: T } | { ok: false; error: WordBankApiError };

export const DEFAULT_RETRY_AFTER_SECONDS = 10;
const MAX_RETRY_AFTER_SECONDS = 60;

/** Whole seconds from a Retry-After header, clamped to 1..60; anything unreadable is the default 10. */
export function parseRetryAfter(value: unknown): number {
  const n = typeof value === 'number' ? value : typeof value === 'string' && /^\s*\d+\s*$/.test(value) ? Number(value) : NaN;
  if (!Number.isFinite(n)) return DEFAULT_RETRY_AFTER_SECONDS;
  return Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(1, Math.round(n)));
}

function headerValue(headers: unknown, name: string): unknown {
  if (typeof headers !== 'object' || headers === null) return undefined;
  const get = (headers as { get?: unknown }).get;
  if (typeof get === 'function') {
    const v = (get as (n: string) => unknown).call(headers, name);
    if (v !== undefined && v !== null) return v;
  }
  return (headers as Record<string, unknown>)[name.toLowerCase()];
}

function fallbackKind(status: number): WordBankFallbackKind {
  if (status === 404) return 'not_found';
  if (status === 422 || status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'http';
}

function batchIndex(data: unknown): number | null {
  if (typeof data !== 'object' || data === null) return null;
  const detail = (data as { detail?: unknown }).detail;
  if (typeof detail !== 'object' || detail === null) return null;
  const index = (detail as { index?: unknown }).index;
  return typeof index === 'number' && Number.isInteger(index) && index >= 0 ? index : null;
}

function detailFor(
  code: string | null,
  response: { status: number; data: unknown; headers: unknown },
  expressionError: TagExprError | null,
): WordBankErrorDetail {
  if (!isWordBankServerErrorCode(code) && !isTagServerErrorCode(code)) return { kind: fallbackKind(response.status) };
  switch (code) {
    case 'invalid_word':
      return { kind: code, index: batchIndex(response.data) };
    case 'related_words_warming':
      return { kind: code, retryAfterSeconds: parseRetryAfter(headerValue(response.headers, 'Retry-After')) };
    case 'invalid_tag_expression':
      return { kind: code, expressionError };
    default:
      return { kind: code };
  }
}

/** Map anything a word bank call threw into a WordBankApiError. */
export function toWordBankApiError(err: unknown, operation: WordBankOperation): WordBankApiError {
  if (err instanceof WordBankApiError) return err;
  if (axios.isAxiosError(err)) {
    const response = err.response;
    if (!response) return new WordBankApiError({ kind: 'network' }, operation, err.message || 'Network error');
    const parsed = parseServerDetail(response.data);
    const message = parsed.message ?? err.message ?? `HTTP ${response.status}`;
    return new WordBankApiError(detailFor(parsed.code, response, parsed.expressionError), operation, message, response.status);
  }
  if (err instanceof Error) return new WordBankApiError({ kind: 'http' }, operation, err.message);
  return new WordBankApiError({ kind: 'http' }, operation, String(err));
}

const MESSAGES: Record<WordBankErrorKind, string> = {
  invalid_word: 'Words are 1 to 64 characters: letters, apostrophes, hyphens, at most 4 words',
  word_exists: 'That word is already in the bank',
  word_not_found: 'That word is no longer in the bank',
  too_many_words: 'Too many words: families work on at most 1000 (filter the bank first)',
  related_words_warming: 'Building related-word index…',
  related_words_unavailable: 'Related words are unavailable on this server',
  tag_name_taken: 'A tag with that name already exists',
  tag_not_found: 'That tag no longer exists',
  preset_name_taken: 'A preset with that name already exists',
  preset_not_found: 'That preset no longer exists',
  invalid_tag_expression: 'The filter is not valid',
  empty_preset: 'A preset needs a non-empty expression',
  invalid_tag_name: 'Tag names are 1 to 100 characters without control characters',
  invalid_color: 'Colors are #rrggbb',
  invalid_preset_name: 'Preset names are 1 to 100 characters without control characters',
  preset_expression_too_long: "Renaming would make a preset's expression too long; pick a shorter name",
  not_found: 'Not found',
  validation: 'The request was rejected',
  network: 'Could not reach the server',
  server: 'The server failed; try again',
  http: 'The request failed',
};

/**
 * A short user-facing line. `batch` (the words sent) lets an `invalid_word` with an index name the word.
 * Contract codes get fixed wording; fallbacks add the server text.
 */
export function describeWordBankError(error: WordBankApiError, batch?: readonly string[]): string {
  const d = error.detail;
  switch (d.kind) {
    case 'invalid_word': {
      const word = d.index !== null && batch !== undefined ? batch[d.index] : undefined;
      const base = word !== undefined ? `"${word}" is not a valid word` : MESSAGES.invalid_word;
      return error.message ? `${base}: ${error.message}` : base;
    }
    case 'invalid_tag_expression':
      return d.expressionError !== null
        ? `${MESSAGES.invalid_tag_expression}: ${d.expressionError.message} (at ${d.expressionError.position})`
        : MESSAGES.invalid_tag_expression;
    case 'not_found':
    case 'validation':
    case 'server':
    case 'http':
      return error.message ? `${MESSAGES[d.kind]}: ${error.message}` : MESSAGES[d.kind];
    default:
      return MESSAGES[d.kind];
  }
}
