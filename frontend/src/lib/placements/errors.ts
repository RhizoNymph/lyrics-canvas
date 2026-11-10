/**
 * Typed failures of the canvas rename and snippet placement endpoints (contract §2 Errors). Every server code
 * is a `PlacementServerErrorCode`; client-side refusals and generic HTTP failures have their own kinds.
 */
import axios from 'axios';
import { parseServerDetail } from '@/lib/tags/serverDetail';

export const PLACEMENT_SERVER_ERROR_CODES = [
  'invalid_canvas_title',
  'placement_not_found',
  'canvas_not_found',
  'same_canvas',
] as const;

export type PlacementServerErrorCode = (typeof PLACEMENT_SERVER_ERROR_CODES)[number];

/**
 * - a server code from the contract (`invalid_canvas_title` is also used for a local title check);
 * - `no_canvas`: no canvas is loaded;
 * - `nothing_selected`: the action got no snippet ids;
 * - `too_many_snippets`: a transfer of more than 500 snippets;
 * - `not_found` / `validation` / `server` / `http`: a status without a contract code;
 * - `network`: no response at all.
 */
export type PlacementErrorKind =
  | PlacementServerErrorCode
  | 'no_canvas'
  | 'nothing_selected'
  | 'too_many_snippets'
  | 'not_found'
  | 'validation'
  | 'network'
  | 'server'
  | 'http';

export type PlacementOperation =
  | 'create_canvas'
  | 'rename_canvas'
  | 'list_canvases'
  | 'update_snippet'
  | 'save_positions'
  | 'remove_from_canvas'
  | 'delete_everywhere'
  | 'transfer';

export function isPlacementServerErrorCode(value: unknown): value is PlacementServerErrorCode {
  return typeof value === 'string' && (PLACEMENT_SERVER_ERROR_CODES as readonly string[]).includes(value);
}

export class PlacementError extends Error {
  readonly kind: PlacementErrorKind;
  readonly operation: PlacementOperation;
  readonly status: number | null;

  constructor(kind: PlacementErrorKind, operation: PlacementOperation, message: string, status: number | null = null) {
    super(message);
    this.name = 'PlacementError';
    this.kind = kind;
    this.operation = operation;
    this.status = status;
  }
}

function kindForStatus(status: number): PlacementErrorKind {
  if (status === 404) return 'not_found';
  if (status === 422 || status === 400) return 'validation';
  if (status >= 500) return 'server';
  return 'http';
}

/** Map anything a placement or canvas API call threw into a PlacementError. */
export function toPlacementError(err: unknown, operation: PlacementOperation): PlacementError {
  if (err instanceof PlacementError) return err;
  if (axios.isAxiosError(err)) {
    const response = err.response;
    if (!response) return new PlacementError('network', operation, err.message || 'Network error');
    const detail = parseServerDetail(response.data);
    const kind = isPlacementServerErrorCode(detail.code) ? detail.code : kindForStatus(response.status);
    return new PlacementError(kind, operation, detail.message ?? err.message ?? `HTTP ${response.status}`, response.status);
  }
  if (err instanceof Error) return new PlacementError('http', operation, err.message);
  return new PlacementError('http', operation, String(err));
}

const MESSAGES: Record<PlacementErrorKind, string> = {
  invalid_canvas_title: 'Canvas titles are 1 to 255 characters',
  placement_not_found: 'That snippet is no longer on this canvas; reload the canvas',
  canvas_not_found: 'That canvas no longer exists',
  same_canvas: 'The snippets are already on this canvas; pick another one',
  no_canvas: 'No canvas is open',
  nothing_selected: 'Select at least one snippet',
  too_many_snippets: 'Move or show at most 500 snippets at a time',
  not_found: 'Not found',
  validation: 'The request was rejected',
  network: 'Could not reach the server',
  server: 'The server failed; try again',
  http: 'The request failed',
};

/** A short user-facing line for the error. */
export function describePlacementError(error: PlacementError): string {
  // A local title check carries the precise problem ("empty", "too long").
  if (error.kind === 'invalid_canvas_title' && error.status === null && error.message) return error.message;
  if (isPlacementServerErrorCode(error.kind) || error.kind === 'network') return MESSAGES[error.kind];
  return error.message ? `${MESSAGES[error.kind]}: ${error.message}` : MESSAGES[error.kind];
}

export type PlacementResult<T> = { ok: true; value: T } | { ok: false; error: PlacementError };
