/** Parse errors of the tag expression language. Codes and positions are part of the shared contract. */
import type { CpOffset } from './text';

export const TAG_EXPR_ERROR_CODES = [
  'too_long',
  'unexpected_character',
  'unterminated_string',
  'invalid_escape',
  'empty_name',
  'unknown_type',
  'unexpected_token',
  'unexpected_end',
  'unclosed_paren',
  'empty_group',
  'too_deep',
] as const;

export type TagExprErrorCode = (typeof TAG_EXPR_ERROR_CODES)[number];

export interface TagExprError {
  readonly code: TagExprErrorCode;
  /** Unicode code point offset into the input. */
  readonly position: CpOffset;
  /** Human-readable; not part of the contract. */
  readonly message: string;
}

export const MAX_EXPRESSION_LENGTH = 500;
export const MAX_PAREN_DEPTH = 32;

const MESSAGES: Record<TagExprErrorCode, string> = {
  too_long: `Expression is longer than ${MAX_EXPRESSION_LENGTH} characters`,
  unexpected_character: 'A backslash is only allowed inside quotes',
  unterminated_string: 'Missing closing quote',
  invalid_escape: 'Only \\" and \\\\ are allowed after a backslash',
  empty_name: 'Quoted tag name is empty',
  unknown_type: 'Unknown type; use verse, chorus, bridge, hook or freestyle',
  unexpected_token: 'Unexpected token',
  unexpected_end: 'Expression ends early; a tag or type is missing',
  unclosed_paren: 'This parenthesis is never closed',
  empty_group: 'Empty parentheses',
  too_deep: `Parentheses nest deeper than ${MAX_PAREN_DEPTH}`,
};

export function tagExprError(code: TagExprErrorCode, position: CpOffset): TagExprError {
  return { code, position, message: MESSAGES[code] };
}

export function isTagExprErrorCode(value: unknown): value is TagExprErrorCode {
  return typeof value === 'string' && (TAG_EXPR_ERROR_CODES as readonly string[]).includes(value);
}
