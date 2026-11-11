/**
 * Typed failures of `GET /api/search` (rest-search contract §3b) and the local query check. The server's
 * only contract code is `invalid_search_query` (422); other failures are classed by status, and no response
 * at all is `network`.
 */
import axios from 'axios';
import { parseServerDetail } from '@/lib/tags/serverDetail';

export const MAX_GLOBAL_QUERY_LENGTH = 200;

export type SearchErrorKind = 'invalid_search_query' | 'validation' | 'network' | 'server' | 'http';

export class SearchError extends Error {
  readonly kind: SearchErrorKind;
  readonly status: number | null;

  constructor(kind: SearchErrorKind, message: string, status: number | null = null) {
    super(message);
    this.name = 'SearchError';
    this.kind = kind;
    this.status = status;
  }
}

function kindForStatus(status: number): SearchErrorKind {
  if (status === 422 || status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'http';
}

/** Map anything the search call threw into a SearchError. */
export function toSearchError(err: unknown): SearchError {
  if (err instanceof SearchError) return err;
  if (axios.isAxiosError(err)) {
    const response = err.response;
    if (!response) return new SearchError('network', err.message || 'Network error');
    const detail = parseServerDetail(response.data);
    const kind: SearchErrorKind = detail.code === 'invalid_search_query' ? 'invalid_search_query' : kindForStatus(response.status);
    return new SearchError(kind, detail.message ?? err.message ?? `HTTP ${response.status}`, response.status);
  }
  if (err instanceof Error) return new SearchError('http', err.message);
  return new SearchError('http', String(err));
}

const MESSAGES: Record<SearchErrorKind, string> = {
  invalid_search_query: `Search for 1 to ${MAX_GLOBAL_QUERY_LENGTH} characters`,
  validation: 'The search was rejected',
  network: 'Could not reach the server',
  server: 'Search failed on the server; try again',
  http: 'Search failed',
};

/** A short user-facing line for the error. */
export function describeSearchError(error: SearchError): string {
  if (error.kind === 'invalid_search_query') return error.message || MESSAGES.invalid_search_query;
  if (error.kind === 'network') return MESSAGES.network;
  return error.message ? `${MESSAGES[error.kind]}: ${error.message}` : MESSAGES[error.kind];
}

export type GlobalQueryCheck =
  | { readonly kind: 'blank' }
  | { readonly kind: 'query'; readonly q: string }
  | { readonly kind: 'invalid'; readonly error: SearchError };

/** The page's input as a request: blank (show the normal list), a trimmed 1..200 query, or a local refusal. */
export function checkGlobalQuery(raw: string): GlobalQueryCheck {
  const q = raw.trim();
  const length = Array.from(q).length;
  if (length === 0) return { kind: 'blank' };
  if (length > MAX_GLOBAL_QUERY_LENGTH) {
    return { kind: 'invalid', error: new SearchError('invalid_search_query', MESSAGES.invalid_search_query) };
  }
  return { kind: 'query', q };
}
