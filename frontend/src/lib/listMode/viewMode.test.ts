import { describe, it, expect, vi } from 'vitest';
import { parseViewMode, loadViewMode, saveViewMode, VIEW_MODE_STORAGE_KEY } from './viewMode';
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

describe('parseViewMode', () => {
  it('accepts the two modes and rejects anything else', () => {
    expect(parseViewMode('canvas')).toBe('canvas');
    expect(parseViewMode('list')).toBe('list');
    expect(parseViewMode('grid')).toBeNull();
    expect(parseViewMode(null)).toBeNull();
  });
});

describe('loadViewMode', () => {
  it('reads a stored mode', () => {
    expect(loadViewMode(memoryStorage({ [VIEW_MODE_STORAGE_KEY]: 'list' }))).toBe('list');
  });

  it('defaults to canvas when nothing, garbage, no storage, or a throwing storage', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadViewMode(memoryStorage())).toBe('canvas');
    expect(loadViewMode(memoryStorage({ [VIEW_MODE_STORAGE_KEY]: 'zzz' }))).toBe('canvas');
    expect(loadViewMode(null)).toBe('canvas');
    expect(loadViewMode(throwing)).toBe('canvas');
  });
});

describe('saveViewMode', () => {
  it('writes the mode and reports success', () => {
    const s = memoryStorage();
    expect(saveViewMode(s, 'list')).toEqual({ ok: true });
    expect(s.data[VIEW_MODE_STORAGE_KEY]).toBe('list');
  });

  it('returns a typed failure instead of throwing', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(saveViewMode(null, 'list')).toEqual({ ok: false, error: { kind: 'unavailable' } });
    expect(saveViewMode(throwing, 'list')).toMatchObject({ ok: false, error: { kind: 'write_failed' } });
  });
});
