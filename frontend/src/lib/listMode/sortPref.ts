/**
 * The chosen list sort, remembered per device (browser storage, like the view mode), not per canvas and never
 * on the server. Reads never throw (fallback: Last edited); writes return a typed result.
 */
import type { KeyValueStorage, SaveViewModeResult } from './viewMode';
import { DEFAULT_LIST_SORT, parseListSort } from './sort';
import type { ListSort } from './sort';

export const LIST_SORT_STORAGE_KEY = 'lyrics-canvas.listSort';

export type SaveListSortResult = SaveViewModeResult;

export function loadListSort(storage: KeyValueStorage | null): ListSort {
  if (storage === null) return DEFAULT_LIST_SORT;
  let raw: string | null;
  try {
    raw = storage.getItem(LIST_SORT_STORAGE_KEY);
  } catch (error) {
    console.warn('list sort: read failed, using default', { key: LIST_SORT_STORAGE_KEY, error });
    return DEFAULT_LIST_SORT;
  }
  return parseListSort(raw) ?? DEFAULT_LIST_SORT;
}

export function saveListSort(storage: KeyValueStorage | null, sort: ListSort): SaveListSortResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(LIST_SORT_STORAGE_KEY, sort);
    return { ok: true };
  } catch (cause) {
    console.warn('list sort: write failed', { key: LIST_SORT_STORAGE_KEY, sort, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
