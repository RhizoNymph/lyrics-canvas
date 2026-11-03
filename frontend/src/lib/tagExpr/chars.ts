/** Character classes of the lexer. Whitespace is exactly Python's `str.isspace` (shared with the backend). */

const SPACE_CODE_POINTS: ReadonlySet<number> = new Set([
  0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
  0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
]);

/** `ch` is one code point. */
export function isSpace(ch: string): boolean {
  return SPACE_CODE_POINTS.has(ch.codePointAt(0) ?? -1);
}

export const OPERATOR_CHARS = '()&|!';

export function isOperatorChar(ch: string): boolean {
  return ch.length === 1 && OPERATOR_CHARS.includes(ch);
}

/** A code point that can be part of a bare word. */
export function isWordChar(ch: string): boolean {
  return !isSpace(ch) && !isOperatorChar(ch) && ch !== '"' && ch !== '\\';
}

export const KEYWORDS = { and: '&', or: '|', not: '!' } as const;
export type Keyword = keyof typeof KEYWORDS;

export function asKeyword(word: string): Keyword | null {
  const lower = word.toLowerCase();
  return lower === 'and' || lower === 'or' || lower === 'not' ? lower : null;
}

export const TYPE_PREFIX = 'type:';
