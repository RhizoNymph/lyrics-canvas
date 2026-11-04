/**
 * Client mirror of the word text rules (contract §2, `invalid_word`) and the add box splitter. The
 * server is authoritative; this catches bad input before a request and explains it inline.
 *
 * Normalize: NFC, strip the ends, collapse every internal whitespace run to one space (whitespace is
 * Python's `str.isspace` set, as the server uses). Valid: 1-64 code points, at most 4 space-separated
 * words, only letters (`\p{L}`), apostrophes (' and ’), hyphens (-) and spaces, at least one letter.
 */
import { codePoints } from '@/lib/tagExpr';
import { isSpace } from '@/lib/tagExpr/chars';

export const MAX_WORD_LENGTH = 64;
export const MAX_WORDS_PER_ENTRY = 4;
export const MAX_BATCH_WORDS = 200;

export type WordProblem =
  | { readonly kind: 'empty' }
  | { readonly kind: 'too_long' }
  | { readonly kind: 'too_many_words' }
  | { readonly kind: 'invalid_character'; readonly char: string }
  | { readonly kind: 'no_letter' };

export type WordCheck = { readonly ok: true; readonly text: string } | { readonly ok: false; readonly problem: WordProblem };

const LETTER = /^\p{L}$/u;
const ALLOWED_MARKS = new Set(["'", '’', '-', ' ']);

export function normalizeWordText(raw: string): string {
  const out: string[] = [];
  let pendingSpace = false;
  for (const ch of codePoints(raw.normalize('NFC'))) {
    if (isSpace(ch)) {
      pendingSpace = out.length > 0;
      continue;
    }
    if (pendingSpace) out.push(' ');
    pendingSpace = false;
    out.push(ch);
  }
  return out.join('');
}

export function checkWordText(raw: string): WordCheck {
  const text = normalizeWordText(raw);
  const chars = codePoints(text);
  if (chars.length === 0) return { ok: false, problem: { kind: 'empty' } };
  if (chars.length > MAX_WORD_LENGTH) return { ok: false, problem: { kind: 'too_long' } };
  if (text.split(' ').length > MAX_WORDS_PER_ENTRY) return { ok: false, problem: { kind: 'too_many_words' } };
  let letters = 0;
  for (const ch of chars) {
    if (LETTER.test(ch)) letters += 1;
    else if (!ALLOWED_MARKS.has(ch)) return { ok: false, problem: { kind: 'invalid_character', char: ch } };
  }
  if (letters === 0) return { ok: false, problem: { kind: 'no_letter' } };
  return { ok: true, text };
}

/** Whether `raw` would be accepted as a bank word (used to enable "+ bank" on rhyme candidates). */
export function isValidWordText(raw: string): boolean {
  return checkWordText(raw).ok;
}

export function describeWordProblem(problem: WordProblem): string {
  switch (problem.kind) {
    case 'empty':
      return 'Type a word';
    case 'too_long':
      return `At most ${MAX_WORD_LENGTH} characters`;
    case 'too_many_words':
      return `At most ${MAX_WORDS_PER_ENTRY} words per entry`;
    case 'invalid_character':
      return `Only letters, apostrophes and hyphens; "${problem.char}" is not allowed`;
    case 'no_letter':
      return 'Needs at least one letter';
  }
}

/** Commas and newlines separate entries; spaces do not (phrases are allowed). Empty pieces are dropped. */
export function splitAddInput(raw: string): string[] {
  return raw
    .split(/[,\r\n]/)
    .map((piece) => piece.trim())
    .filter((piece) => piece.length > 0);
}

export type BatchCheck =
  | { readonly ok: true; readonly words: readonly string[] }
  | { readonly ok: false; readonly reason: 'empty' }
  | { readonly ok: false; readonly reason: 'too_many' }
  | { readonly ok: false; readonly reason: 'invalid_word'; readonly index: number; readonly problem: WordProblem };

/** Validate a batch for `POST /api/word-bank`: 1..200 words, each valid; the first bad index wins. */
export function checkBatch(raw: readonly string[]): BatchCheck {
  if (raw.length === 0) return { ok: false, reason: 'empty' };
  if (raw.length > MAX_BATCH_WORDS) return { ok: false, reason: 'too_many' };
  const words: string[] = [];
  for (let index = 0; index < raw.length; index += 1) {
    const check = checkWordText(raw[index]);
    if (!check.ok) return { ok: false, reason: 'invalid_word', index, problem: check.problem };
    words.push(check.text);
  }
  return { ok: true, words };
}

/** The add box's inline message: the first entry that would be rejected, or null when all are fine. */
export function addInputProblem(raw: string): { readonly index: number; readonly entry: string; readonly message: string } | null {
  const entries = splitAddInput(raw);
  if (entries.length > MAX_BATCH_WORDS) return { index: MAX_BATCH_WORDS, entry: entries[MAX_BATCH_WORDS], message: `At most ${MAX_BATCH_WORDS} words at once` };
  for (let index = 0; index < entries.length; index += 1) {
    const check = checkWordText(entries[index]);
    if (!check.ok) return { index, entry: entries[index], message: `"${entries[index]}": ${describeWordProblem(check.problem)}` };
  }
  return null;
}

/** More than this many selected words asks before deleting. */
export const DELETE_CONFIRM_THRESHOLD = 5;

export function needsDeleteConfirm(count: number): boolean {
  return count > DELETE_CONFIRM_THRESHOLD;
}
