/**
 * Typed failures of the tag and tag-preset endpoints. Every server code in the contract is a member of
 * `TagServerErrorCode`; anything else the client can hit has its own `TagApiErrorKind`.
 */
import axios from 'axios';
import type { TagExprError } from '@/lib/tagExpr';
import { parseServerDetail } from './serverDetail';

export const TAG_SERVER_ERROR_CODES = [
  'tag_name_taken',
  'tag_not_found',
  'preset_name_taken',
  'preset_not_found',
  'invalid_tag_expression',
  'empty_preset',
  'invalid_tag_name',
  'invalid_color',
  'invalid_preset_name',
] as const;

/** `detail.error` values the tag and preset endpoints return. */
export type TagServerErrorCode = (typeof TAG_SERVER_ERROR_CODES)[number];

/**
 * - a server code from the contract;
 * - `not_found`: 404 without a contract code (e.g. a snippet that is gone when linking);
 * - `validation`: 422 without a contract code (FastAPI request validation);
 * - `network`: no response at all;
 * - `server`: 5xx without a contract code;
 * - `http`: any other failure.
 */
export type TagApiErrorKind = TagServerErrorCode | 'not_found' | 'validation' | 'network' | 'server' | 'http';

export type TagOperation =
  | 'load_tags'
  | 'create_tag'
  | 'update_tag'
  | 'delete_tag'
  | 'link_tag'
  | 'unlink_tag'
  | 'load_presets'
  | 'create_preset'
  | 'update_preset'
  | 'delete_preset';

export function isTagServerErrorCode(value: unknown): value is TagServerErrorCode {
  return typeof value === 'string' && (TAG_SERVER_ERROR_CODES as readonly string[]).includes(value);
}

export class TagApiError extends Error {
  readonly kind: TagApiErrorKind;
  readonly operation: TagOperation;
  readonly status: number | null;
  /** Set for `invalid_tag_expression` when the server sent the position. */
  readonly expressionError: TagExprError | null;

  constructor(
    kind: TagApiErrorKind,
    operation: TagOperation,
    message: string,
    status: number | null = null,
    expressionError: TagExprError | null = null,
  ) {
    super(message);
    this.name = 'TagApiError';
    this.kind = kind;
    this.operation = operation;
    this.status = status;
    this.expressionError = expressionError;
  }
}

function kindForStatus(status: number): TagApiErrorKind {
  if (status === 404) return 'not_found';
  if (status === 422 || status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'http';
}

/** Map anything a tag or preset API call threw into a TagApiError. */
export function toTagApiError(err: unknown, operation: TagOperation): TagApiError {
  if (err instanceof TagApiError) return err;
  if (axios.isAxiosError(err)) {
    const response = err.response;
    if (!response) return new TagApiError('network', operation, err.message || 'Network error');
    const detail = parseServerDetail(response.data);
    const kind = isTagServerErrorCode(detail.code) ? detail.code : kindForStatus(response.status);
    const message = detail.message ?? err.message ?? `HTTP ${response.status}`;
    return new TagApiError(kind, operation, message, response.status, detail.expressionError);
  }
  if (err instanceof Error) return new TagApiError('http', operation, err.message);
  return new TagApiError('http', operation, String(err));
}

const MESSAGES: Record<TagApiErrorKind, string> = {
  tag_name_taken: 'A tag with that name already exists',
  tag_not_found: 'That tag no longer exists',
  preset_name_taken: 'A preset with that name already exists',
  preset_not_found: 'That preset no longer exists',
  invalid_tag_expression: 'The expression is not valid',
  empty_preset: 'A preset needs a non-empty expression',
  invalid_tag_name: 'Tag names are 1 to 100 characters without control characters',
  invalid_color: 'Colors are #rrggbb',
  invalid_preset_name: 'Preset names are 1 to 100 characters without control characters',
  not_found: 'Not found',
  validation: 'The request was rejected',
  network: 'Could not reach the server',
  server: 'The server failed; try again',
  http: 'The request failed',
};

/** A short user-facing line for the error (contract codes get fixed wording; the rest add the server text). */
export function describeTagError(error: TagApiError): string {
  if (error.kind === 'invalid_tag_expression' && error.expressionError !== null) {
    return `${MESSAGES.invalid_tag_expression}: ${error.expressionError.message} (at ${error.expressionError.position})`;
  }
  if (isTagServerErrorCode(error.kind) || error.kind === 'network') return MESSAGES[error.kind];
  return error.message ? `${MESSAGES[error.kind]}: ${error.message}` : MESSAGES[error.kind];
}

export type TagResult<T> = { ok: true; value: T } | { ok: false; error: TagApiError };
