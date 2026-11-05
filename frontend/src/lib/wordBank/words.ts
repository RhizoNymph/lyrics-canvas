/** Immutable operations on the loaded bank (an array kept newest first, like `GET /api/word-bank`). */
import { evaluate } from '@/lib/tagExpr';
import type { TagExprOrEmpty } from '@/lib/tagExpr';
import { byName } from '@/lib/tags';
import type { SnippetTag } from '@/types';
import type { BankWord } from '@/types/wordBank';

/** The server's order: created_at desc, then id. */
export function byNewest(a: BankWord, b: BankWord): number {
  if (a.created_at !== b.created_at) return a.created_at < b.created_at ? 1 : -1;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/** Insert or replace the incoming words (by id); the result is newest first. */
export function mergeWords(words: readonly BankWord[], incoming: readonly BankWord[]): readonly BankWord[] {
  if (incoming.length === 0) return words;
  const byId = new Map(words.map((w) => [w.id, w]));
  for (const w of incoming) byId.set(w.id, w);
  return [...byId.values()].sort(byNewest);
}

export function removeWords(words: readonly BankWord[], ids: ReadonlySet<string>): readonly BankWord[] {
  return words.filter((w) => !ids.has(w.id));
}

function mapWords(words: readonly BankWord[], update: (w: BankWord) => BankWord): readonly BankWord[] {
  let changed = false;
  const next = words.map((w) => {
    const u = update(w);
    if (u !== w) changed = true;
    return u;
  });
  return changed ? next : words;
}

/** Replace the embedded copy of a renamed or recolored tag; word tags stay sorted by lower(name). */
export function patchWordTag(words: readonly BankWord[], tag: SnippetTag): readonly BankWord[] {
  return mapWords(words, (w) =>
    w.tags.some((t) => t.id === tag.id) ? { ...w, tags: w.tags.map((t) => (t.id === tag.id ? tag : t)).sort(byName) } : w,
  );
}

/** Drop a deleted tag from every word. */
export function removeWordTag(words: readonly BankWord[], tagId: string): readonly BankWord[] {
  return mapWords(words, (w) => (w.tags.some((t) => t.id === tagId) ? { ...w, tags: w.tags.filter((t) => t.id !== tagId) } : w));
}

export function isWordVisible(word: BankWord, applied: TagExprOrEmpty): boolean {
  return evaluate(applied, { type: null, tagNames: word.tags.map((t) => t.name) });
}

/** The words the applied filter shows, in list order. No filter returns the same array. */
export function visibleWords(words: readonly BankWord[], applied: TagExprOrEmpty): readonly BankWord[] {
  if (applied === null) return words;
  return words.filter((w) => isWordVisible(w, applied));
}

/** The selected words in list order. */
export function selectedInOrder(words: readonly BankWord[], ids: ReadonlySet<string>): BankWord[] {
  return words.filter((w) => ids.has(w.id));
}

/** How many more words carry `tagId` in `after` than in `before` (words matched by id). */
export function tagLinkDelta(before: readonly BankWord[], after: readonly BankWord[], tagId: string): number {
  const had = new Set(before.filter((w) => w.tags.some((t) => t.id === tagId)).map((w) => w.id));
  let delta = 0;
  for (const w of after) {
    const has = w.tags.some((t) => t.id === tagId);
    if (has && !had.has(w.id)) delta += 1;
    if (!has && had.has(w.id)) delta -= 1;
  }
  return delta;
}
