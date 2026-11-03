/**
 * Recursive-descent parser. Precedence `!` > `&` > `|`; juxtaposition is AND.
 *
 *   or      := and ( "|" and )*
 *   and     := unary ( "&"? unary )*
 *   unary   := "!" unary | primary
 *   primary := "(" or ")" | TAG | TYPE
 */
import type { TagExpr, TagExprOrEmpty } from './ast';
import { MAX_PAREN_DEPTH, tagExprError } from './errors';
import type { TagExprError } from './errors';
import { lex } from './lexer';
import type { Token } from './lexer';
import { cp, cpLength } from './text';

export type ParseResult = { ok: true; expr: TagExprOrEmpty } | { ok: false; error: TagExprError };

/** Thrown only inside this module and always caught by `parse`. */
class ParseFailure {
  constructor(readonly error: TagExprError) {}
}

function flatten(kind: 'and' | 'or', items: TagExpr[]): TagExpr {
  if (items.length === 1) return items[0];
  const children: TagExpr[] = [];
  for (const item of items) {
    if (item.kind === kind) children.push(...item.children);
    else children.push(item);
  }
  return { kind, children };
}

const STARTS_OPERAND: ReadonlySet<Token['kind']> = new Set(['not', 'lparen', 'tag', 'type']);

export function parse(input: string): ParseResult {
  const lexed = lex(input);
  if (!lexed.ok) return lexed;
  const tokens = lexed.tokens;
  if (tokens.length === 0) return { ok: true, expr: null };
  const inputEnd = cp(cpLength(input));
  let pos = 0;

  const peek = (): Token | undefined => tokens[pos];
  const fail = (error: TagExprError): never => {
    throw new ParseFailure(error);
  };

  const parseOr = (depth: number): TagExpr => {
    const items = [parseAnd(depth)];
    while (peek()?.kind === 'or') {
      pos++;
      items.push(parseAnd(depth));
    }
    return flatten('or', items);
  };

  const parseAnd = (depth: number): TagExpr => {
    const items = [parseUnary(depth)];
    for (;;) {
      const t = peek();
      if (t?.kind === 'and') {
        pos++;
        items.push(parseUnary(depth));
      } else if (t !== undefined && STARTS_OPERAND.has(t.kind)) {
        items.push(parseUnary(depth));
      } else {
        break;
      }
    }
    return flatten('and', items);
  };

  const parseUnary = (depth: number): TagExpr => {
    if (peek()?.kind === 'not') {
      pos++;
      return { kind: 'not', child: parseUnary(depth) };
    }
    return parsePrimary(depth);
  };

  const parsePrimary = (depth: number): TagExpr => {
    const t = tokens[pos++];
    if (t === undefined) return fail(tagExprError('unexpected_end', inputEnd));
    switch (t.kind) {
      case 'lparen': {
        if (depth + 1 > MAX_PAREN_DEPTH) return fail(tagExprError('too_deep', t.start));
        if (peek()?.kind === 'rparen') return fail(tagExprError('empty_group', t.start));
        const inner = parseOr(depth + 1);
        const close = tokens[pos++];
        if (close === undefined) return fail(tagExprError('unclosed_paren', t.start));
        if (close.kind !== 'rparen') return fail(tagExprError('unexpected_token', close.start));
        return inner;
      }
      case 'tag':
        return { kind: 'tag', name: t.name };
      case 'type':
        return { kind: 'type', value: t.value };
      default:
        return fail(tagExprError('unexpected_token', t.start));
    }
  };

  try {
    const expr = parseOr(0);
    const rest = peek();
    if (rest !== undefined) return { ok: false, error: tagExprError('unexpected_token', rest.start) };
    return { ok: true, expr };
  } catch (e) {
    if (e instanceof ParseFailure) return { ok: false, error: e.error };
    throw e;
  }
}
