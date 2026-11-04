/** Lexer: turns the whole input into tokens before parsing (lexer errors win over parse errors). */
import type { SnippetType } from '@/types';
import { isSnippetType } from './ast';
import { asKeyword, isOperatorChar, isSpace, isWordChar, TYPE_PREFIX } from './chars';
import { MAX_EXPRESSION_LENGTH, tagExprError } from './errors';
import type { TagExprError } from './errors';
import { codePoints, cp } from './text';
import type { CpOffset } from './text';

export type OperatorKind = 'lparen' | 'rparen' | 'and' | 'or' | 'not';

interface Span {
  /** Code point offset of the first character. */
  readonly start: CpOffset;
  /** Code point offset one past the last character. */
  readonly end: CpOffset;
}

export type Token =
  | (Span & { readonly kind: OperatorKind; /** Spelled as a word (`and`) rather than a symbol. */ readonly word: boolean })
  | (Span & { readonly kind: 'tag'; readonly name: string; readonly quoted: boolean })
  | (Span & { readonly kind: 'type'; readonly value: SnippetType });

export type LexResult = { ok: true; tokens: Token[] } | { ok: false; error: TagExprError };

const SYMBOLS: Record<string, OperatorKind> = { '(': 'lparen', ')': 'rparen', '&': 'and', '|': 'or', '!': 'not' };
const KEYWORD_KINDS = { and: 'and', or: 'or', not: 'not' } as const;

export function lex(input: string): LexResult {
  const chars = codePoints(input);
  if (chars.length > MAX_EXPRESSION_LENGTH) return { ok: false, error: tagExprError('too_long', cp(MAX_EXPRESSION_LENGTH)) };
  const tokens: Token[] = [];
  let i = 0;
  while (i < chars.length) {
    const c = chars[i];
    if (isSpace(c)) {
      i++;
    } else if (isOperatorChar(c)) {
      tokens.push({ kind: SYMBOLS[c], word: false, start: cp(i), end: cp(i + 1) });
      i++;
    } else if (c === '"') {
      const start = i;
      i++;
      let name = '';
      for (;;) {
        if (i >= chars.length) return { ok: false, error: tagExprError('unterminated_string', cp(start)) };
        const d = chars[i];
        if (d === '\\') {
          const next = chars[i + 1];
          if (next === '"' || next === '\\') {
            name += next;
            i += 2;
            continue;
          }
          return { ok: false, error: tagExprError('invalid_escape', cp(i)) };
        }
        i++;
        if (d === '"') break;
        name += d;
      }
      if (codePoints(name).every(isSpace)) return { ok: false, error: tagExprError('empty_name', cp(start)) };
      tokens.push({ kind: 'tag', name, quoted: true, start: cp(start), end: cp(i) });
    } else if (c === '\\') {
      return { ok: false, error: tagExprError('unexpected_character', cp(i)) };
    } else {
      const start = i;
      while (i < chars.length && isWordChar(chars[i])) i++;
      const word = chars.slice(start, i).join('');
      const span = { start: cp(start), end: cp(i) };
      const keyword = asKeyword(word);
      if (keyword !== null) {
        tokens.push({ kind: KEYWORD_KINDS[keyword], word: true, ...span });
      } else if (word.toLowerCase().startsWith(TYPE_PREFIX)) {
        const value = chars.slice(start + TYPE_PREFIX.length, i).join('').toLowerCase();
        if (!isSnippetType(value)) return { ok: false, error: tagExprError('unknown_type', cp(start)) };
        tokens.push({ kind: 'type', value, ...span });
      } else {
        tokens.push({ kind: 'tag', name: word, quoted: false, ...span });
      }
    }
  }
  return { ok: true, tokens };
}
