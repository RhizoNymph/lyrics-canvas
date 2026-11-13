/**
 * Typed failures of the pattern view: the rhyme-pattern endpoints' codes (`pattern_range_invalid`,
 * `pattern_span_invalid`, `pattern_slot_order`, `invalid_tag_expression`), status classes, no response
 * (`network`), and failures of the canvas / word bank actions started from the view.
 */
import axios from 'axios';
import { parseServerDetail } from '@/lib/tags/serverDetail';
import type { TagExprError } from '@/lib/tagExpr/errors';

export type PatternErrorKind =
  | 'network'
  | 'not_found'
  | 'range_invalid'
  | 'span_invalid'
  | 'slot_order'
  | 'invalid_tag_filter'
  | 'invalid_request'
  | 'server'
  | 'unknown';

export type PatternOperation = 'detect' | 'suggest' | 'more' | 'make_snippet' | 'materialize';

export class PatternError extends Error {
  readonly kind: PatternErrorKind;
  readonly operation: PatternOperation;
  readonly status: number | null;
  /** Set only for `invalid_tag_filter`. */
  readonly expressionError: TagExprError | null;

  constructor(kind: PatternErrorKind, operation: PatternOperation, message: string, status: number | null = null, expressionError: TagExprError | null = null) {
    super(message);
    this.name = 'PatternError';
    this.kind = kind;
    this.operation = operation;
    this.status = status;
    this.expressionError = expressionError;
  }
}

const CODE_KINDS: Readonly<Record<string, PatternErrorKind>> = {
  pattern_range_invalid: 'range_invalid',
  pattern_span_invalid: 'span_invalid',
  pattern_slot_order: 'slot_order',
  invalid_tag_expression: 'invalid_tag_filter',
};

function kindForStatus(status: number): PatternErrorKind {
  if (status === 404) return 'not_found';
  if (status === 422 || status === 400) return 'invalid_request';
  return 'server';
}

export function toPatternError(err: unknown, operation: PatternOperation): PatternError {
  if (err instanceof PatternError) return err;
  if (axios.isAxiosError(err)) {
    const response = err.response;
    if (response === undefined) return new PatternError('network', operation, err.message || 'Network error');
    const detail = parseServerDetail(response.data);
    const coded = detail.code === null ? undefined : CODE_KINDS[detail.code];
    const kind = coded ?? kindForStatus(response.status);
    return new PatternError(kind, operation, detail.message ?? err.message, response.status, kind === 'invalid_tag_filter' ? detail.expressionError : null);
  }
  if (err instanceof Error) return new PatternError('unknown', operation, err.message);
  return new PatternError('unknown', operation, String(err));
}

const OPERATION_LABELS: Record<PatternOperation, string> = {
  detect: 'Finding the pattern failed',
  suggest: 'Searching failed',
  more: 'Loading more failed',
  make_snippet: 'Make snippet failed',
  materialize: 'Materialize failed',
};

const CAUSES: Record<Exclude<PatternErrorKind, 'invalid_tag_filter'>, string> = {
  network: 'could not reach the server',
  not_found: 'not found',
  range_invalid: 'the line range is not valid',
  span_invalid: 'a slot word is no longer in the lines',
  slot_order: 'the slots are out of order on a line',
  invalid_request: 'the request was rejected',
  server: 'server error',
  unknown: 'unexpected error',
};

/** One user-facing line. Span and order errors suggest Reset (the snippet text likely changed). */
export function describePatternError(error: PatternError): string {
  if (error.kind === 'invalid_tag_filter') return `Not searched: ${error.message}`;
  const base = `${OPERATION_LABELS[error.operation]}: ${CAUSES[error.kind]}`;
  const detail = error.kind === 'network' || error.message === '' ? '' : ` (${error.message})`;
  const hint = error.kind === 'span_invalid' || error.kind === 'slot_order' ? ' Try Reset.' : '';
  return `${base}${detail}.${hint}`;
}
