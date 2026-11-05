/** The "Make snippet" draft, the add result line, and the editor selection read for "Add to word bank". */
import type { SnippetType } from '@/types';
import type { BankWord, BankWordCreateResponse } from '@/types/wordBank';

export const DEFAULT_DRAFT_TYPE: SnippetType = 'freestyle';

export interface SnippetDraft {
  readonly text: string;
  readonly type: SnippetType;
}

/** One word per line, in the order given (the list order of the selection). */
export function draftFromWords(words: readonly BankWord[]): SnippetDraft {
  return { text: words.map((w) => w.text).join('\n'), type: DEFAULT_DRAFT_TYPE };
}

export function isDraftCreatable(text: string): boolean {
  return text.trim() !== '';
}

export function addSummary(result: BankWordCreateResponse): string {
  return `Added ${result.created.length}, ${result.existing.length} already in bank`;
}

/** The selected text of an input or textarea (DOM offsets), '' without a selection. */
export function selectedFieldText(value: string, start: number | null, end: number | null): string {
  if (start === null || end === null || start === end) return '';
  return value.slice(Math.min(start, end), Math.max(start, end));
}
