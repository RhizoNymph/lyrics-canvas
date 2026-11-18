/** Mapping failures of the score draft / export calls into `ScoreApiError`, and their wording. */
import axios from 'axios';
import { ScoreApiError, isScoreInvalidReason, isScoreServerErrorCode } from '@/types/scoreTools';
import type { ScoreApiErrorKind, ScoreApiOperation, ScoreServerErrorCode } from '@/types/scoreTools';
import type { ScoreInvalidReason } from '@/types/score';

interface ParsedBody {
  code: ScoreServerErrorCode | null;
  message: string | null;
  invalid: { reason: ScoreInvalidReason; line: number | null; note: number | null } | null;
}

const EMPTY: ParsedBody = { code: null, message: null, invalid: null };

function intOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) ? value : null;
}

/** `{detail: {error, message?, reason?, line?, note?}}` (or the same object at the top level). */
function parseBody(data: unknown): ParsedBody {
  if (typeof data !== 'object' || data === null) return EMPTY;
  const detail = 'detail' in data ? (data as { detail: unknown }).detail : data;
  if (typeof detail === 'string') return { ...EMPTY, message: detail };
  if (typeof detail !== 'object' || detail === null || Array.isArray(detail)) return EMPTY;
  const fields = detail as { error?: unknown; message?: unknown; reason?: unknown; line?: unknown; note?: unknown };
  const code = isScoreServerErrorCode(fields.error) ? fields.error : null;
  const message = typeof fields.message === 'string' ? fields.message : null;
  if (code !== 'invalid_score') return { code, message, invalid: null };
  // An unknown reason (a newer server) still is an invalid score; `malformed` is the catch-all shape code.
  const reason = isScoreInvalidReason(fields.reason) ? fields.reason : 'malformed';
  return { code, message, invalid: { reason, line: intOrNull(fields.line), note: intOrNull(fields.note) } };
}

function kindForStatus(status: number): ScoreApiErrorKind {
  switch (status) {
    case 404:
      return 'not_found';
    case 422:
      return 'invalid_request';
    case 502:
    case 503:
    case 504:
      return 'server_unavailable';
    default:
      return 'http';
  }
}

/** Map anything a score API call threw into a ScoreApiError; a known code in the body wins over the status. */
export function toScoreApiError(err: unknown, operation: ScoreApiOperation): ScoreApiError {
  if (err instanceof ScoreApiError) return err;
  if (axios.isAxiosError(err)) {
    if (err.code === axios.AxiosError.ERR_CANCELED) return new ScoreApiError('aborted', operation, 'Cancelled');
    const response = err.response;
    if (!response) return new ScoreApiError('network', operation, err.message || 'Network error');
    const { code, message, invalid } = parseBody(response.data);
    const kind = code ?? kindForStatus(response.status);
    return new ScoreApiError(kind, operation, message ?? err.message ?? `HTTP ${response.status}`, response.status, invalid);
  }
  if (err instanceof Error) return new ScoreApiError('http', operation, err.message);
  return new ScoreApiError('http', operation, String(err));
}

/** What a dialog or notice shows for a failure, and whether trying again makes sense. */
export interface ScoreErrorDescription {
  title: string;
  hint: string | null;
  retryable: boolean;
}

const DESCRIPTIONS: Readonly<Record<ScoreApiErrorKind, ScoreErrorDescription>> = {
  snippet_not_found: { title: 'The snippet no longer exists', hint: null, retryable: false },
  score_not_found: { title: 'This snippet has no score to export', hint: 'Make a score first.', retryable: false },
  recording_not_found: { title: 'This recording no longer exists', hint: 'Pick another take.', retryable: false },
  recording_has_no_timings: {
    title: 'This take has no word timings',
    hint: 'Takes recorded before word timings were stored cannot be drafted; record a new take.',
    retryable: false,
  },
  draft_empty: {
    title: 'Nothing could be placed',
    hint: 'The snippet has no syllables, or no word of the take matched the lyrics.',
    retryable: false,
  },
  invalid_score: { title: 'The drafted score is invalid', hint: null, retryable: false },
  invalid_request: { title: 'The server rejected the draft settings', hint: 'Check the meter and tempo.', retryable: false },
  not_found: { title: 'Scores are not available on this server', hint: null, retryable: false },
  server_unavailable: { title: 'The server is not responding', hint: null, retryable: true },
  network: { title: 'Could not reach the server', hint: 'Check your connection.', retryable: true },
  aborted: { title: 'Cancelled', hint: null, retryable: true },
  http: { title: 'The request failed', hint: null, retryable: true },
};

export function describeScoreApiError(error: ScoreApiError): ScoreErrorDescription {
  const base = DESCRIPTIONS[error.kind];
  if (error.kind !== 'invalid_score' || error.reason === null) return base;
  const where = [
    error.line !== null ? `line ${error.line + 1}` : null,
    error.note !== null ? `note ${error.note + 1}` : null,
  ].filter((x): x is string => x !== null);
  return { ...base, hint: where.length > 0 ? `${error.reason} (${where.join(', ')})` : error.reason };
}
