/**
 * Evaluate an expression against one subject: a snippet, or a typeless word bank word (`type: null`, for
 * which every `type:` term is false). Tag names compare under simple lowercasing.
 */
import type { SnippetType } from '@/types';
import type { TagExpr, TagExprOrEmpty } from './ast';

export interface EvalSubject {
  /** null for a subject without a type (a word bank word). */
  readonly type: SnippetType | null;
  readonly tagNames: readonly string[];
}

function evalNode(e: TagExpr, type: SnippetType | null, lowerNames: ReadonlySet<string>): boolean {
  switch (e.kind) {
    case 'tag':
      return lowerNames.has(e.name.toLowerCase());
    case 'type':
      return e.value === type;
    case 'not':
      return !evalNode(e.child, type, lowerNames);
    case 'and':
      return e.children.every((c) => evalNode(c, type, lowerNames));
    case 'or':
      return e.children.some((c) => evalNode(c, type, lowerNames));
  }
}

/** The empty expression matches everything; unknown tag names match nothing. */
export function evaluate(expr: TagExprOrEmpty, subject: EvalSubject): boolean {
  if (expr === null) return true;
  return evalNode(expr, subject.type, new Set(subject.tagNames.map((n) => n.toLowerCase())));
}
