/**
 * AST of the tag expression language (docs/features/tag_expressions.md). Nested same-kind And/Or are
 * flattened by the parser and parentheses leave no node. The empty expression (matches everything) is
 * `null` wherever a `TagExprOrEmpty` is accepted, never a node.
 */
import type { SnippetType } from '@/types';

export const SNIPPET_TYPES: readonly SnippetType[] = ['verse', 'chorus', 'bridge', 'hook', 'freestyle'];

export type TagExpr =
  | { readonly kind: 'or'; readonly children: readonly TagExpr[] }
  | { readonly kind: 'and'; readonly children: readonly TagExpr[] }
  | { readonly kind: 'not'; readonly child: TagExpr }
  | { readonly kind: 'tag'; readonly name: string }
  | { readonly kind: 'type'; readonly value: SnippetType };

/** A parsed expression; `null` is the empty expression, which matches every snippet. */
export type TagExprOrEmpty = TagExpr | null;

export function isSnippetType(value: string): value is SnippetType {
  return (SNIPPET_TYPES as readonly string[]).includes(value);
}
