import { describe, it, expect, vi } from 'vitest';
import { LIST_SORT_STORAGE_KEY, loadListSort, saveListSort } from './sortPref';
import { VIEW_MODE_STORAGE_KEY } from './viewMode';
import type { KeyValueStorage } from './viewMode';

function memoryStorage(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
  };
}

const throwing: KeyValueStorage = {
  getItem: () => {
    throw new Error('SecurityError');
  },
  setItem: () => {
    throw new Error('QuotaExceededError');
  },
};

describe('list sort persistence', () => {
  it('uses its own per-device key next to the view mode', () => {
    expect(LIST_SORT_STORAGE_KEY).toBe('lyrics-canvas.listSort');
    expect(LIST_SORT_STORAGE_KEY).not.toBe(VIEW_MODE_STORAGE_KEY);
  });

  it('round-trips every sort', () => {
    const storage = memoryStorage();
    for (const sort of ['last_edited', 'created', 'a_z', 'custom'] as const) {
      expect(saveListSort(storage, sort)).toEqual({ ok: true });
      expect(storage.data[LIST_SORT_STORAGE_KEY]).toBe(sort);
      expect(loadListSort(storage)).toBe(sort);
    }
  });

  it('defaults to last_edited when nothing, garbage, no storage, or a throwing storage', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadListSort(memoryStorage())).toBe('last_edited');
    expect(loadListSort(memoryStorage({ [LIST_SORT_STORAGE_KEY]: 'shuffle' }))).toBe('last_edited');
    expect(loadListSort(null)).toBe('last_edited');
    expect(loadListSort(throwing)).toBe('last_edited');
  });

  it('reports typed failures instead of throwing on save', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(saveListSort(null, 'custom')).toEqual({ ok: false, error: { kind: 'unavailable' } });
    const result = saveListSort(throwing, 'custom');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.kind).toBe('write_failed');
  });
});
