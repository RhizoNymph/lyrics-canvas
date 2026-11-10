import { describe, it, expect } from 'vitest';
import type { KeyValueStorage } from '@/lib/listMode';
import {
  DEFAULT_VOCAB_HINTS,
  VOCAB_HINTS_STORAGE_KEY,
  loadVocabHints,
  parseVocabHints,
  saveVocabHints,
} from './vocabHintsSetting';

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
    throw new Error('SecurityError');
  },
  setItem: () => {
    throw new Error('QuotaExceededError');
  },
};

describe('vocabulary hints setting', () => {
  it('uses the contract key and defaults to on', () => {
    expect(VOCAB_HINTS_STORAGE_KEY).toBe('lyrics-canvas.voice.vocabHints');
    expect(DEFAULT_VOCAB_HINTS).toBe(true);
    expect(loadVocabHints(memory())).toBe(true);
    expect(loadVocabHints(null)).toBe(true);
  });

  it('parses only the two stored values', () => {
    expect(parseVocabHints('true')).toBe(true);
    expect(parseVocabHints('false')).toBe(false);
    expect(parseVocabHints(null)).toBeNull();
    expect(parseVocabHints('')).toBeNull();
    expect(parseVocabHints('0')).toBeNull();
    expect(parseVocabHints('yes')).toBeNull();
  });

  it('round-trips through storage', () => {
    const s = memory();
    expect(saveVocabHints(s, false)).toEqual({ ok: true });
    expect(s.data[VOCAB_HINTS_STORAGE_KEY]).toBe('false');
    expect(loadVocabHints(s)).toBe(false);
    saveVocabHints(s, true);
    expect(loadVocabHints(s)).toBe(true);
  });

  it('falls back to the default on garbage or a throwing storage', () => {
    expect(loadVocabHints(memory({ [VOCAB_HINTS_STORAGE_KEY]: 'maybe' }))).toBe(true);
    expect(loadVocabHints(throwing)).toBe(true);
  });

  it('reports typed write failures', () => {
    expect(saveVocabHints(null, true)).toEqual({ ok: false, error: { kind: 'unavailable' } });
    const r = saveVocabHints(throwing, false);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.kind).toBe('write_failed');
  });
});
