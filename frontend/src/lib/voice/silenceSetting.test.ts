import { describe, it, expect, vi } from 'vitest';
import {
  DEFAULT_SILENCE_SECONDS,
  SILENCE_CHOICES,
  SILENCE_STORAGE_KEY,
  loadSilenceSeconds,
  parseSilenceSeconds,
  saveSilenceSeconds,
  silenceLabel,
} from './silenceSetting';
import type { KeyValueStorage } from '@/lib/listMode';

function memory(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
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
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('quota');
  },
};

describe('silence setting', () => {
  it('offers off/2/3/5/8/10 s and defaults to off', () => {
    expect(SILENCE_CHOICES).toEqual([0, 2, 3, 5, 8, 10]);
    expect(DEFAULT_SILENCE_SECONDS).toBe(0);
    expect(silenceLabel(0)).toBe('Off');
    expect(silenceLabel(5)).toBe('5 s');
  });

  it('parses only the offered choices', () => {
    expect(parseSilenceSeconds('3')).toBe(3);
    expect(parseSilenceSeconds('0')).toBe(0);
    expect(parseSilenceSeconds('4')).toBeNull();
    expect(parseSilenceSeconds('abc')).toBeNull();
    expect(parseSilenceSeconds(null)).toBeNull();
    expect(parseSilenceSeconds('')).toBeNull();
  });

  it('round-trips through storage', () => {
    const s = memory();
    expect(loadSilenceSeconds(s)).toBe(0);
    expect(saveSilenceSeconds(s, 8)).toEqual({ ok: true });
    expect(s.data[SILENCE_STORAGE_KEY]).toBe('8');
    expect(loadSilenceSeconds(s)).toBe(8);
  });

  it('falls back to the default for missing, garbage or throwing storage', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadSilenceSeconds(null)).toBe(0);
    expect(loadSilenceSeconds(memory({ [SILENCE_STORAGE_KEY]: '7' }))).toBe(0);
    expect(loadSilenceSeconds(throwing)).toBe(0);
    expect(saveSilenceSeconds(null, 2)).toEqual({ ok: false, error: { kind: 'unavailable' } });
    const failed = saveSilenceSeconds(throwing, 2);
    expect(failed.ok).toBe(false);
    expect(!failed.ok && failed.error.kind).toBe('write_failed');
    warn.mockRestore();
  });
});
