import { describe, it, expect, vi } from 'vitest';
import { filterStorageKey, loadFilter, parsePersistedFilter, saveFilter } from './persistence';
import type { FilterStorage } from './persistence';

function memory(): FilterStorage & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  };
}

const throwing: FilterStorage = {
  getItem: () => {
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('quota');
  },
  removeItem: () => {
    throw new Error('blocked');
  },
};

describe('tag filter persistence', () => {
  it('stores per canvas and reads back', () => {
    const s = memory();
    expect(saveFilter(s, 'c1', { text: 'love &', appliedText: 'love' })).toEqual({ ok: true });
    expect(saveFilter(s, 'c2', { text: 'night', appliedText: 'night' })).toEqual({ ok: true });
    expect(loadFilter(s, 'c1')).toEqual({ text: 'love &', appliedText: 'love' });
    expect(loadFilter(s, 'c2')).toEqual({ text: 'night', appliedText: 'night' });
    expect(loadFilter(s, 'c3')).toBeNull();
    expect([...s.data.keys()].sort()).toEqual([filterStorageKey('c1'), filterStorageKey('c2')]);
  });

  it('removes the key for an empty filter', () => {
    const s = memory();
    saveFilter(s, 'c1', { text: 'x', appliedText: 'x' });
    saveFilter(s, 'c1', { text: '', appliedText: '' });
    expect(s.data.size).toBe(0);
  });

  it('ignores malformed or foreign values', () => {
    expect(parsePersistedFilter('not json')).toBeNull();
    expect(parsePersistedFilter('{"v":2,"text":"a","appliedText":"a"}')).toBeNull();
    expect(parsePersistedFilter('{"v":1,"text":3,"appliedText":"a"}')).toBeNull();
    expect(parsePersistedFilter('null')).toBeNull();
  });

  it('reports missing or failing storage without throwing', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadFilter(null, 'c1')).toBeNull();
    expect(saveFilter(null, 'c1', { text: 'a', appliedText: 'a' })).toEqual({ ok: false, error: { kind: 'unavailable' } });
    expect(loadFilter(throwing, 'c1')).toBeNull();
    expect(saveFilter(throwing, 'c1', { text: 'a', appliedText: 'a' })).toMatchObject({ ok: false, error: { kind: 'write_failed' } });
    warn.mockRestore();
  });
});

describe('filter persistence at a fixed key (word bank)', () => {
  it('stores under lyrics-canvas.wordBankFilter, apart from canvas filters', async () => {
    const { WORD_BANK_FILTER_KEY, loadFilterAt, saveFilterAt } = await import('./persistence');
    expect(WORD_BANK_FILTER_KEY).toBe('lyrics-canvas.wordBankFilter');
    const s = memory();
    saveFilter(s, 'c1', { text: 'a', appliedText: 'a' });
    expect(saveFilterAt(s, WORD_BANK_FILTER_KEY, { text: 'love &', appliedText: 'love' })).toEqual({ ok: true });
    expect(loadFilterAt(s, WORD_BANK_FILTER_KEY)).toEqual({ text: 'love &', appliedText: 'love' });
    expect(loadFilter(s, 'c1')).toEqual({ text: 'a', appliedText: 'a' });
    saveFilterAt(s, WORD_BANK_FILTER_KEY, { text: '', appliedText: '' });
    expect(s.data.has(WORD_BANK_FILTER_KEY)).toBe(false);
  });

  it('survives throwing storage', async () => {
    const { loadFilterAt, saveFilterAt } = await import('./persistence');
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadFilterAt(throwing, 'k')).toBeNull();
    expect(saveFilterAt(throwing, 'k', { text: 'x', appliedText: 'x' }).ok).toBe(false);
    expect(saveFilterAt(null, 'k', { text: 'x', appliedText: 'x' })).toEqual({ ok: false, error: { kind: 'unavailable' } });
  });
});
