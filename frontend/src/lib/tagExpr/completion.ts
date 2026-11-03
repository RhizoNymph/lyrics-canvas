/**
 * Autocomplete at the caret: tag names, `type:` values, and keywords where they fit. Pure; the input
 * component converts DOM (UTF-16) carets to code points before calling in.
 */
import type { SnippetType } from '@/types';
import { SNIPPET_TYPES } from './ast';
import { isSpace, TYPE_PREFIX } from './chars';
import { caretToken, slotAt } from './caretContext';
import type { GrammarSlot } from './caretContext';
import { formatTagName } from './format';
import { codePoints, cp } from './text';
import type { CpOffset } from './text';

export interface CompletionContext {
  readonly replaceStart: CpOffset;
  readonly replaceEnd: CpOffset;
  readonly prefix: string;
  readonly quoted: boolean;
  readonly slot: GrammarSlot;
}

export type Suggestion =
  | { readonly kind: 'tag'; readonly label: string; readonly insertText: string; readonly color: string }
  | { readonly kind: 'type'; readonly label: string; readonly insertText: string; readonly value: SnippetType }
  | { readonly kind: 'keyword'; readonly label: 'and' | 'or' | 'not'; readonly insertText: string };

export interface CompletionTag {
  readonly name: string;
  readonly color: string;
}

export const DEFAULT_SUGGESTION_LIMIT = 10;

export function completionContextAt(text: string, caret: CpOffset): CompletionContext {
  const chars = codePoints(text);
  const token = caretToken(chars, caret);
  return {
    replaceStart: token.start,
    replaceEnd: token.end,
    prefix: token.prefix,
    quoted: token.quoted,
    slot: slotAt(chars, token.start),
  };
}

/** Prefix matches first, then substring matches; each group keeps the input order. */
function rankByPrefix<T>(items: readonly T[], key: (t: T) => string, query: string): T[] {
  if (query === '') return [...items];
  const prefix: T[] = [];
  const inner: T[] = [];
  for (const item of items) {
    const k = key(item).toLowerCase();
    if (k.startsWith(query)) prefix.push(item);
    else if (k.includes(query)) inner.push(item);
  }
  return [...prefix, ...inner];
}

export function suggestionsFor(
  ctx: CompletionContext,
  tags: readonly CompletionTag[],
  limit: number = DEFAULT_SUGGESTION_LIMIT,
): Suggestion[] {
  const query = ctx.prefix.toLowerCase();
  const tagItems = (): Suggestion[] =>
    rankByPrefix(tags, (t) => t.name, query).map((t) => ({
      kind: 'tag',
      label: t.name,
      insertText: formatTagName(t.name),
      color: t.color,
    }));
  const typeItem = (value: SnippetType): Suggestion => ({
    kind: 'type',
    label: `${TYPE_PREFIX}${value}`,
    insertText: `${TYPE_PREFIX}${value}`,
    value,
  });
  const keyword = (label: 'and' | 'or' | 'not'): Suggestion => ({ kind: 'keyword', label, insertText: label });

  if (ctx.quoted) return tagItems().slice(0, limit);

  if (query.startsWith(TYPE_PREFIX)) {
    const rest = query.slice(TYPE_PREFIX.length);
    return SNIPPET_TYPES.filter((v) => v.startsWith(rest)).map(typeItem).slice(0, limit);
  }

  const types =
    query === '' || TYPE_PREFIX.startsWith(query)
      ? SNIPPET_TYPES.map(typeItem)
      : SNIPPET_TYPES.filter((v) => v.startsWith(query)).map(typeItem);
  const binary =
    ctx.slot === 'operand_or_operator' ? (['and', 'or'] as const).filter((k) => k.startsWith(query)).map(keyword) : [];
  const not = query !== '' && 'not'.startsWith(query) ? [keyword('not')] : [];

  // After a complete operand a binary operator is the likeliest next word, so it leads.
  const ordered = [...binary, ...tagItems(), ...types, ...not];
  return ordered.slice(0, limit);
}

export interface TextEdit {
  readonly text: string;
  /** Where the caret goes after the edit, in code points. */
  readonly caret: CpOffset;
}

/** A term inserted after `last` (one code point, or '' at the start) needs a separating space. */
function needsLeadingSpace(last: string): boolean {
  return last !== '' && !isSpace(last) && last !== '(' && last !== '!';
}

/** Replace the context's token with the suggestion and put the caret after it (and a following space). */
export function applySuggestion(text: string, ctx: CompletionContext, suggestion: Suggestion): TextEdit {
  const chars = codePoints(text);
  const before = chars.slice(0, ctx.replaceStart);
  const after = chars.slice(ctx.replaceEnd);
  const lead = needsLeadingSpace(before[before.length - 1] ?? '') ? ' ' : '';
  const next = after[0];
  const head = before.join('') + lead + suggestion.insertText;
  const headLength = codePoints(head).length;
  if (next === undefined || (!isSpace(next) && next !== ')')) {
    return { text: `${head} ${after.join('')}`, caret: cp(headLength + 1) };
  }
  return { text: head + after.join(''), caret: cp(headLength + (isSpace(next) ? 1 : 0)) };
}
