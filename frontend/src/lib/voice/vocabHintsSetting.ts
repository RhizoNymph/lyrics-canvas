/**
 * The recorder's "Use word bank as hints" switch (dataset contract §4), per viewer in browser storage.
 * Default on. Storage can be missing or throw: reads then use the default and writes return a typed
 * failure, so the in-memory value still applies for the session.
 */
import type { KeyValueStorage } from '@/lib/listMode';

export const VOCAB_HINTS_STORAGE_KEY = 'lyrics-canvas.voice.vocabHints';
export const DEFAULT_VOCAB_HINTS = true;

export type VocabHintsStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };
export type SaveVocabHintsResult = { ok: true } | { ok: false; error: VocabHintsStorageError };

export function parseVocabHints(raw: string | null): boolean | null {
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  return null;
}

export function loadVocabHints(storage: KeyValueStorage | null): boolean {
  if (storage === null) return DEFAULT_VOCAB_HINTS;
  let raw: string | null;
  try {
    raw = storage.getItem(VOCAB_HINTS_STORAGE_KEY);
  } catch (error) {
    console.warn('voice vocab hints setting: read failed, using default', { key: VOCAB_HINTS_STORAGE_KEY, error });
    return DEFAULT_VOCAB_HINTS;
  }
  return parseVocabHints(raw) ?? DEFAULT_VOCAB_HINTS;
}

export function saveVocabHints(storage: KeyValueStorage | null, enabled: boolean): SaveVocabHintsResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(VOCAB_HINTS_STORAGE_KEY, String(enabled));
    return { ok: true };
  } catch (cause) {
    console.warn('voice vocab hints setting: write failed', { key: VOCAB_HINTS_STORAGE_KEY, enabled, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
