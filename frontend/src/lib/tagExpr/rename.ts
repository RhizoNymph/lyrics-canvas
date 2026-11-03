/** Rename a tag inside an expression (what the server does to presets when a tag is renamed). */
import type { TagExpr, TagExprOrEmpty } from './ast';
import { format } from './format';
import { parse } from './parser';

function renameNode(e: TagExpr, fromLower: string, to: string): TagExpr {
  switch (e.kind) {
    case 'tag':
      return e.name.toLowerCase() === fromLower ? { kind: 'tag', name: to } : e;
    case 'type':
      return e;
    case 'not':
      return { kind: 'not', child: renameNode(e.child, fromLower, to) };
    case 'and':
    case 'or':
      return { kind: e.kind, children: e.children.map((c) => renameNode(c, fromLower, to)) };
  }
}

/** Replace every Tag whose lowercase equals lowercase(`from`) with Tag(`to`). Type terms are untouched. */
export function renameTag(expr: TagExprOrEmpty, from: string, to: string): TagExprOrEmpty {
  return expr === null ? null : renameNode(expr, from.toLowerCase(), to);
}

export function mentionsTag(expr: TagExprOrEmpty, name: string): boolean {
  const lower = name.toLowerCase();
  const walk = (e: TagExpr): boolean => {
    switch (e.kind) {
      case 'tag':
        return e.name.toLowerCase() === lower;
      case 'type':
        return false;
      case 'not':
        return walk(e.child);
      case 'and':
      case 'or':
        return e.children.some(walk);
    }
  };
  return expr !== null && walk(expr);
}

/**
 * Rename in expression text. Returns the normalized renamed text, or the input unchanged when it does
 * not parse or does not mention `from` (so a user's own spacing survives unrelated renames).
 */
export function renameInText(text: string, from: string, to: string): string {
  const parsed = parse(text);
  if (!parsed.ok || !mentionsTag(parsed.expr, from)) return text;
  return format(renameTag(parsed.expr, from, to));
}
