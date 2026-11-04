/** Insert a tag at the caret (tag pill click), joined to its neighbours with ` & ` where needed. */
import { isSpace } from './chars';
import { caretToken, slotAt, startsOperandAt } from './caretContext';
import type { TextEdit } from './completion';
import { formatTagName } from './format';
import { codePoints, cp } from './text';
import type { CpOffset } from './text';

export function insertTagAtCaret(text: string, caret: CpOffset, name: string): TextEdit {
  const chars = codePoints(text);
  // Never split a word or a quoted name: insert after the token the caret is in.
  const token = caretToken(chars, caret);
  const at = token.start < caret && caret < token.end ? token.end : Math.min(caret, chars.length);
  const term = formatTagName(name);

  const before = chars.slice(0, at);
  while (before.length > 0 && isSpace(before[before.length - 1])) before.pop();
  const last = before[before.length - 1];
  let head: string;
  if (last === undefined) head = term;
  else if (slotAt(chars, at) === 'operand_or_operator') head = `${before.join('')} & ${term}`;
  else if (last === '(' || last === '!') head = before.join('') + term;
  else head = `${before.join('')} ${term}`;

  const after = chars.slice(at);
  while (after.length > 0 && isSpace(after[0])) after.shift();
  const caretAt = cp(codePoints(head).length);
  if (after.length === 0) return { text: head, caret: caretAt };
  if (startsOperandAt(chars, at)) return { text: `${head} & ${after.join('')}`, caret: caretAt };
  if (after[0] === ')') return { text: head + after.join(''), caret: caretAt };
  return { text: `${head} ${after.join('')}`, caret: caretAt };
}
