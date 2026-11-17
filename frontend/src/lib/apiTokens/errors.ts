/**
 * Typed failures of `/api/tokens` (round-3 contract §C). The contract codes are `invalid_token_name` (422),
 * `token_not_found` (404) and `token_management_forbidden` (403, the request was authenticated by an API
 * token rather than a login). Other failures are classed by status; no response at all is `network`.
 */
import axios from 'axios';
import { parseServerDetail } from '@/lib/tags/serverDetail';

export type ApiTokenErrorKind =
  | 'invalid_token_name'
  | 'token_not_found'
  | 'token_management_forbidden'
  | 'validation'
  | 'network'
  | 'aborted'
  | 'server'
  | 'http';

const CONTRACT_CODES: readonly ApiTokenErrorKind[] = ['invalid_token_name', 'token_not_found', 'token_management_forbidden'];

export class ApiTokenError extends Error {
  readonly kind: ApiTokenErrorKind;
  readonly status: number | null;

  constructor(kind: ApiTokenErrorKind, message: string, status: number | null = null) {
    super(message);
    this.name = 'ApiTokenError';
    this.kind = kind;
    this.status = status;
  }
}

function kindForStatus(status: number): ApiTokenErrorKind {
  if (status === 422 || status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'http';
}

function contractKind(code: string | null): ApiTokenErrorKind | null {
  return CONTRACT_CODES.find((k) => k === code) ?? null;
}

/** Map anything a token call threw into an ApiTokenError. */
export function toApiTokenError(err: unknown): ApiTokenError {
  if (err instanceof ApiTokenError) return err;
  if (axios.isCancel(err)) return new ApiTokenError('aborted', 'Request cancelled');
  if (axios.isAxiosError(err)) {
    if (err.code === 'ERR_CANCELED') return new ApiTokenError('aborted', 'Request cancelled');
    const response = err.response;
    if (!response) return new ApiTokenError('network', err.message || 'Network error');
    const detail = parseServerDetail(response.data);
    const kind = contractKind(detail.code) ?? kindForStatus(response.status);
    return new ApiTokenError(kind, detail.message ?? err.message ?? `HTTP ${response.status}`, response.status);
  }
  if (err instanceof Error) return new ApiTokenError('http', err.message);
  return new ApiTokenError('http', String(err));
}

const MESSAGES: Record<ApiTokenErrorKind, string> = {
  invalid_token_name: 'Name the token (1 to 64 characters)',
  token_not_found: 'That token no longer exists',
  token_management_forbidden: 'Tokens can only be managed from a signed-in session, not with an API token',
  validation: 'The request was rejected',
  network: 'Could not reach the server',
  aborted: 'Cancelled',
  server: 'The server failed; try again',
  http: 'Request failed',
};

/** A short user-facing line for the error. Contract codes use fixed wording; others append the server text. */
export function describeApiTokenError(error: ApiTokenError): string {
  if (contractKind(error.kind) !== null || error.kind === 'network' || error.kind === 'aborted') return MESSAGES[error.kind];
  return error.message ? `${MESSAGES[error.kind]}: ${error.message}` : MESSAGES[error.kind];
}
