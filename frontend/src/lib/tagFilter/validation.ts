/** What the expression input shows under an invalid text: the message and the text split at the error. */
import { codePoints, parse } from '@/lib/tagExpr';
import type { CpOffset, TagExprError } from '@/lib/tagExpr';

export interface ErrorMarker {
  readonly error: TagExprError;
  /** Text before the error position (possibly elided at the front). */
  readonly before: string;
  /** The code point at the error, or '' when the error is at the end. */
  readonly at: string;
  /** Text after it (possibly elided at the end). */
  readonly after: string;
  readonly position: CpOffset;
}

export const MARKER_CONTEXT = 24;

export function errorMarker(text: string, error: TagExprError, context: number = MARKER_CONTEXT): ErrorMarker {
  const chars = codePoints(text);
  const p = Math.min(error.position, chars.length);
  const from = Math.max(0, p - context);
  const to = Math.min(chars.length, p + 1 + context);
  return {
    error,
    position: error.position,
    before: (from > 0 ? '…' : '') + chars.slice(from, p).join(''),
    at: chars[p] ?? '',
    after: chars.slice(p + 1, to).join('') + (to < chars.length ? '…' : ''),
  };
}

/** Null when the text parses. */
export function markerFor(text: string): ErrorMarker | null {
  const parsed = parse(text);
  return parsed.ok ? null : errorMarker(text, parsed.error);
}
