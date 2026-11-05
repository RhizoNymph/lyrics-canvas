/**
 * Filter texts per viewer (browser storage): one per canvas (`filterStorageKey`), plus the word bank's
 * user-wide one (`WORD_BANK_FILTER_KEY`). Storage can be missing or throw; reads then
 * fall back to no filter and writes report a typed failure, so the in-memory filter always works.
 */
import type { PersistedFilter } from './state';

export const FILTER_STORAGE_PREFIX = 'lyrics-canvas.tagFilter.';
/** The word bank's filter, remembered separately from every canvas filter (not per canvas). */
export const WORD_BANK_FILTER_KEY = 'lyrics-canvas.wordBankFilter';

/** The part of `Storage` this module uses. */
export interface FilterStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type FilterStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };
export type SaveFilterResult = { ok: true } | { ok: false; error: FilterStorageError };

export function filterStorageKey(canvasId: string): string {
  return `${FILTER_STORAGE_PREFIX}${canvasId}`;
}

/** `globalThis.localStorage`, or null when absent or when touching it throws. */
export function browserFilterStorage(): FilterStorage | null {
  try {
    return (globalThis as { localStorage?: FilterStorage }).localStorage ?? null;
  } catch (error) {
    console.warn('tag filter: localStorage inaccessible', { error });
    return null;
  }
}

export function parsePersistedFilter(raw: string | null): PersistedFilter | null {
  if (raw === null) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const { v, text, appliedText } = value as { v?: unknown; text?: unknown; appliedText?: unknown };
  if (v !== 1 || typeof text !== 'string' || typeof appliedText !== 'string') return null;
  return { text, appliedText };
}

export function loadFilter(storage: FilterStorage | null, canvasId: string): PersistedFilter | null {
  return loadFilterAt(storage, filterStorageKey(canvasId));
}

/** An all-empty filter removes the key instead of storing it. */
export function saveFilter(storage: FilterStorage | null, canvasId: string, filter: PersistedFilter): SaveFilterResult {
  return saveFilterAt(storage, filterStorageKey(canvasId), filter);
}

export function loadFilterAt(storage: FilterStorage | null, key: string): PersistedFilter | null {
  if (storage === null) return null;
  try {
    return parsePersistedFilter(storage.getItem(key));
  } catch (error) {
    console.warn('tag filter: read failed, using no filter', { key, error });
    return null;
  }
}

/** An all-empty filter removes the key instead of storing it. */
export function saveFilterAt(storage: FilterStorage | null, key: string, filter: PersistedFilter): SaveFilterResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    if (filter.text === '' && filter.appliedText === '') storage.removeItem(key);
    else storage.setItem(key, JSON.stringify({ v: 1, text: filter.text, appliedText: filter.appliedText }));
    return { ok: true };
  } catch (cause) {
    console.warn('tag filter: write failed', { key, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
