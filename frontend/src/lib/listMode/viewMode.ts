/**
 * Which view the canvas page shows, persisted per viewer in browser storage. Storage can be missing
 * or throw (private mode, blocked site data); reads then fall back to the canvas and writes report a
 * typed failure, so the in-memory mode always works.
 */
export type ViewMode = 'canvas' | 'list';

export const VIEW_MODE_STORAGE_KEY = 'lyrics-canvas.viewMode';
export const DEFAULT_VIEW_MODE: ViewMode = 'canvas';

/** The part of `Storage` this module uses. */
export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export type ViewModeStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };

export type SaveViewModeResult = { ok: true } | { ok: false; error: ViewModeStorageError };

export function parseViewMode(raw: string | null): ViewMode | null {
  return raw === 'canvas' || raw === 'list' ? raw : null;
}

/** `globalThis.localStorage`, or null when absent or when touching it throws. */
export function browserStorage(): KeyValueStorage | null {
  try {
    const storage = (globalThis as { localStorage?: KeyValueStorage }).localStorage;
    return storage ?? null;
  } catch (error) {
    console.warn('view mode: localStorage inaccessible', { error });
    return null;
  }
}

export function loadViewMode(storage: KeyValueStorage | null): ViewMode {
  if (storage === null) return DEFAULT_VIEW_MODE;
  let raw: string | null;
  try {
    raw = storage.getItem(VIEW_MODE_STORAGE_KEY);
  } catch (error) {
    console.warn('view mode: read failed, using default', { key: VIEW_MODE_STORAGE_KEY, error });
    return DEFAULT_VIEW_MODE;
  }
  return parseViewMode(raw) ?? DEFAULT_VIEW_MODE;
}

export function saveViewMode(storage: KeyValueStorage | null, mode: ViewMode): SaveViewModeResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(VIEW_MODE_STORAGE_KEY, mode);
    return { ok: true };
  } catch (cause) {
    console.warn('view mode: write failed', { key: VIEW_MODE_STORAGE_KEY, mode, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
