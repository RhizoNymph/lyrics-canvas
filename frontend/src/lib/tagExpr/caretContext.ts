/**
 * Where the caret is, grammatically: which token it is in (to replace on completion) and what the
 * grammar accepts there. Works on any text, valid or not, by looking only at the caret's neighbourhood.
 */
import { asKeyword, isSpace, isWordChar } from './chars';
import { cp } from './text';
import type { CpOffset } from './text';

/**
 * - `operand`: after nothing, `(`, `&`, `|`, `!` or a keyword; only a tag, type, `(` or `!` fits.
 * - `operand_or_operator`: after a complete operand; a binary operator fits, and so does another
 *   operand (juxtaposition is AND).
 */
export type GrammarSlot = 'operand' | 'operand_or_operator';

export interface CaretToken {
  /** First code point of the token under the caret (the opening quote for a quoted name). */
  readonly start: CpOffset;
  /** One past its last code point (past the closing quote when there is one). */
  readonly end: CpOffset;
  /** Text from `start` to the caret; unescaped and without the quote for a quoted name. */
  readonly prefix: string;
  readonly quoted: boolean;
}

/** The open quote containing `caret`, by scanning from the start with the lexer's escape rules. */
function openQuoteBefore(chars: readonly string[], caret: number): number | null {
  let open: number | null = null;
  let i = 0;
  while (i < caret) {
    const c = chars[i];
    if (open === null) {
      if (c === '"') open = i;
      i++;
    } else if (c === '\\') {
      i += 2;
    } else {
      if (c === '"') open = null;
      i++;
    }
  }
  // An escape pair straddling the caret still counts as inside the string.
  return open;
}

function unescapeLenient(raw: readonly string[]): string {
  let out = '';
  for (let i = 0; i < raw.length; i++) {
    if (raw[i] === '\\' && i + 1 < raw.length) {
      out += raw[i + 1];
      i++;
    } else if (raw[i] !== '\\') {
      out += raw[i];
    }
  }
  return out;
}

function closingQuoteFrom(chars: readonly string[], from: number): number | null {
  let i = from;
  while (i < chars.length) {
    if (chars[i] === '\\') i += 2;
    else if (chars[i] === '"') return i;
    else i++;
  }
  return null;
}

export function caretToken(chars: readonly string[], caret: number): CaretToken {
  const at = Math.max(0, Math.min(caret, chars.length));
  const open = openQuoteBefore(chars, at);
  if (open !== null) {
    const close = closingQuoteFrom(chars, at);
    let end = close === null ? at : close + 1;
    if (close === null) while (end < chars.length && isWordChar(chars[end])) end++;
    return { start: cp(open), end: cp(end), prefix: unescapeLenient(chars.slice(open + 1, at)), quoted: true };
  }
  let start = at;
  while (start > 0 && isWordChar(chars[start - 1])) start--;
  let end = at;
  while (end < chars.length && isWordChar(chars[end])) end++;
  return { start: cp(start), end: cp(end), prefix: chars.slice(start, at).join(''), quoted: false };
}

/** What the grammar accepts at `offset`, judged from the last significant token before it. */
export function slotAt(chars: readonly string[], offset: number): GrammarSlot {
  let j = Math.min(offset, chars.length) - 1;
  while (j >= 0 && isSpace(chars[j])) j--;
  if (j < 0) return 'operand';
  const c = chars[j];
  if (c === '(' || c === '&' || c === '|' || c === '!') return 'operand';
  if (c === ')' || c === '"') return 'operand_or_operator';
  if (!isWordChar(c)) return 'operand';
  let k = j;
  while (k > 0 && isWordChar(chars[k - 1])) k--;
  return asKeyword(chars.slice(k, j + 1).join('')) !== null ? 'operand' : 'operand_or_operator';
}

/** True when the text from `offset` on (ignoring whitespace) starts with something that begins an operand. */
export function startsOperandAt(chars: readonly string[], offset: number): boolean {
  let j = offset;
  while (j < chars.length && isSpace(chars[j])) j++;
  if (j >= chars.length) return false;
  const c = chars[j];
  if (c === '(' || c === '!' || c === '"') return true;
  if (!isWordChar(c)) return false;
  let k = j;
  while (k < chars.length && isWordChar(chars[k])) k++;
  const keyword = asKeyword(chars.slice(j, k).join(''));
  return keyword === null || keyword === 'not';
}
