/** Normalized text of an expression: `format(parse(format(x))) == format(x)`. */
import type { TagExpr, TagExprOrEmpty } from './ast';
import { asKeyword, isWordChar, TYPE_PREFIX } from './chars';
import { codePoints } from './text';

/** A tag name that can be written without quotes. */
export function isBareTagName(name: string): boolean {
  if (name.length === 0 || !codePoints(name).every(isWordChar)) return false;
  return asKeyword(name) === null && !name.toLowerCase().startsWith(TYPE_PREFIX);
}

/** The tag name as expression text: bare when possible, else quoted with `\` and `"` escaped. */
export function formatTagName(name: string): string {
  if (isBareTagName(name)) return name;
  return `"${name.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function formatNode(e: TagExpr): string {
  switch (e.kind) {
    case 'tag':
      return formatTagName(e.name);
    case 'type':
      return `${TYPE_PREFIX}${e.value}`;
    case 'not':
      return e.child.kind === 'and' || e.child.kind === 'or' ? `!(${formatNode(e.child)})` : `!${formatNode(e.child)}`;
    case 'and':
      return e.children.map((c) => (c.kind === 'or' ? `(${formatNode(c)})` : formatNode(c))).join(' & ');
    case 'or':
      return e.children.map(formatNode).join(' | ');
  }
}

export function format(expr: TagExprOrEmpty): string {
  return expr === null ? '' : formatNode(expr);
}
