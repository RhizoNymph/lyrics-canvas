/**
 * What a key press in a row's text input means for the row structure (Enter splits, Backspace at
 * caret 0 joins onto the previous row, Delete at the end joins the next row up). Pure, so the
 * caret rules are unit-tested apart from the DOM; the editor applies the matching reducer.
 */

export interface RowKeyInput {
  key: string;
  text: string;
  selectionStart: number;
  selectionEnd: number;
  row: number;
  rowCount: number;
  /** An IME composition is in progress; its keys belong to the IME. */
  composing: boolean;
}

export type RowKeyAction =
  /** Split `text` (the input value with any selected range removed) at `caret`. */
  | { kind: 'split'; text: string; caret: number }
  | { kind: 'joinPrevious' }
  | { kind: 'joinNext' }
  /** Backspace on an empty row: delete it. */
  | { kind: 'deleteEmpty' }
  /** Let the input handle the key. */
  | { kind: 'none' };

const NONE: RowKeyAction = { kind: 'none' };

export function rowKeyAction(input: RowKeyInput): RowKeyAction {
  if (input.composing) return NONE;
  const { text, row, rowCount } = input;
  const start = Math.min(input.selectionStart, input.selectionEnd);
  const end = Math.max(input.selectionStart, input.selectionEnd);
  const collapsed = start === end;
  switch (input.key) {
    case 'Enter':
      return { kind: 'split', text: text.slice(0, start) + text.slice(end), caret: start };
    case 'Backspace':
      if (text === '' && rowCount > 1) return { kind: 'deleteEmpty' };
      if (collapsed && start === 0 && row > 0) return { kind: 'joinPrevious' };
      return NONE;
    case 'Delete':
      if (collapsed && end === text.length && row < rowCount - 1) return { kind: 'joinNext' };
      return NONE;
    default:
      return NONE;
  }
}
