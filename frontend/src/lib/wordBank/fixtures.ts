/** Test fixtures for word bank words. */
import type { SnippetTag } from '@/types';
import type { BankWord } from '@/types/wordBank';

export function wtag(id: string, name: string = id, color = '#000000'): SnippetTag {
  return { id, name, color };
}

/** A bank word; `n` orders created_at (higher = newer). */
export function bw(id: string, text: string, n = 0, tags: SnippetTag[] = []): BankWord {
  return { id, text, tags, created_at: new Date(Date.UTC(2026, 10, 1, 0, 0, n)).toISOString() };
}
