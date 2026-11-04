/**
 * The backend's error body: `{"detail": {"error": <code>, "message": <text>}}`, plus
 * `"expression_error": {code, position, message}` for tag expression errors. FastAPI validation errors
 * (`detail` is a list) and plain-string details are tolerated.
 */
import { cp, isTagExprErrorCode, tagExprError } from '@/lib/tagExpr';
import type { TagExprError } from '@/lib/tagExpr';

export interface ServerErrorDetail {
  /** `detail.error`, or null when the body has none. */
  readonly code: string | null;
  readonly message: string | null;
  readonly expressionError: TagExprError | null;
}

const EMPTY: ServerErrorDetail = { code: null, message: null, expressionError: null };

function parseExpressionError(value: unknown): TagExprError | null {
  if (typeof value !== 'object' || value === null) return null;
  const { code, position, message } = value as { code?: unknown; position?: unknown; message?: unknown };
  if (!isTagExprErrorCode(code) || typeof position !== 'number' || !Number.isInteger(position) || position < 0) return null;
  const base = tagExprError(code, cp(position));
  return typeof message === 'string' && message.length > 0 ? { ...base, message } : base;
}

export function parseServerDetail(data: unknown): ServerErrorDetail {
  if (typeof data !== 'object' || data === null) return EMPTY;
  const detail = (data as { detail?: unknown }).detail;
  if (typeof detail === 'string') return { ...EMPTY, message: detail };
  if (Array.isArray(detail)) {
    const msgs = detail
      .map((d) => (typeof d === 'object' && d !== null ? (d as { msg?: unknown }).msg : null))
      .filter((m): m is string => typeof m === 'string');
    return { ...EMPTY, message: msgs.length > 0 ? msgs.join('; ') : null };
  }
  if (typeof detail !== 'object' || detail === null) return EMPTY;
  const { error, message, expression_error } = detail as { error?: unknown; message?: unknown; expression_error?: unknown };
  return {
    code: typeof error === 'string' ? error : null,
    message: typeof message === 'string' ? message : null,
    expressionError: parseExpressionError(expression_error),
  };
}
