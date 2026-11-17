import { describe, it, expect } from 'vitest';
import {
  BASE_62_DIGITS,
  MAX_RANK_LENGTH,
  RankError,
  compareRanks,
  keyBetween,
  nKeysBetween,
  validateRank,
} from './rank';

describe('keyBetween', () => {
  const cases: Array<[string | null, string | null, string]> = [
    [null, null, 'a0'],
    [null, 'a0', 'Zz'],
    [null, 'Zz', 'Zy'],
    ['a0', null, 'a1'],
    ['a1', null, 'a2'],
    ['a0', 'a1', 'a0V'],
    ['a1', 'a2', 'a1V'],
    ['a0V', 'a1', 'a0l'],
    ['Zz', 'a0', 'ZzV'],
    ['Zz', 'a1', 'a0'],
    [null, 'Y00', 'Xzzz'],
    ['bzz', null, 'c000'],
    ['a0', 'a0V', 'a0G'],
    ['a0', 'a0G', 'a08'],
    ['b125', 'b129', 'b127'],
    ['a0', 'a1V', 'a1'],
    ['Zz', 'a01', 'a0'],
    [null, 'a0V', 'a0'],
    [null, 'b999', 'b99'],
    [null, 'A000000000000000000000000001', 'A000000000000000000000000000V'],
    ['zzzzzzzzzzzzzzzzzzzzzzzzzzy', null, 'zzzzzzzzzzzzzzzzzzzzzzzzzzz'],
    ['zzzzzzzzzzzzzzzzzzzzzzzzzzz', null, 'zzzzzzzzzzzzzzzzzzzzzzzzzzzV'],
    ['az', null, 'b00'],
    [null, 'b00', 'az'],
  ];
  for (const [a, b, expected] of cases) {
    it(`(${a}, ${b}) -> ${expected}`, () => {
      const key = keyBetween(a, b);
      expect(key).toBe(expected);
      if (a !== null) expect(compareRanks(a, key)).toBeLessThan(0);
      if (b !== null) expect(compareRanks(key, b)).toBeLessThan(0);
    });
  }

  const failures: Array<[string | null, string | null]> = [
    [null, 'A00000000000000000000000000'],
    ['a00', null],
    ['a00', 'a1'],
    ['0', '1'],
    ['a1', 'a0'],
    ['a1', 'a1'],
    ['', null],
    [null, 'b1'],
  ];
  for (const [a, b] of failures) {
    it(`(${a}, ${b}) throws RankError`, () => {
      expect(() => keyBetween(a, b)).toThrow(RankError);
    });
  }

  it('reports the failure kind', () => {
    const kind = (a: string | null, b: string | null) => {
      try {
        keyBetween(a, b);
        return null;
      } catch (err) {
        return err instanceof RankError ? err.kind : 'other';
      }
    };
    expect(kind('a1', 'a0')).toBe('out_of_order');
    expect(kind('a1', 'a1')).toBe('out_of_order');
    expect(kind('a00', null)).toBe('invalid_key');
    expect(kind(null, 'A00000000000000000000000000')).toBe('invalid_key');
  });

  it('keeps producing ordered keys along a long chain in either direction', () => {
    let up = keyBetween(null, null);
    let down = up;
    for (let i = 0; i < 2000; i++) {
      const nextUp = keyBetween(up, null);
      expect(compareRanks(up, nextUp)).toBeLessThan(0);
      up = nextUp;
      const nextDown = keyBetween(null, down);
      expect(compareRanks(nextDown, down)).toBeLessThan(0);
      down = nextDown;
    }
  });

  it('bisects repeatedly towards one side without breaking order', () => {
    const low = 'a0';
    let high = 'a1';
    for (let i = 0; i < 200; i++) {
      const mid = keyBetween(low, high);
      expect(compareRanks(low, mid)).toBeLessThan(0);
      expect(compareRanks(mid, high)).toBeLessThan(0);
      expect(validateRank(mid).ok).toBe(true);
      high = mid;
    }
  });
});

describe('nKeysBetween', () => {
  it('returns [] for n = 0 and one key for n = 1', () => {
    expect(nKeysBetween(null, null, 0)).toEqual([]);
    expect(nKeysBetween('a0', 'a1', 1)).toEqual(['a0V']);
  });

  it('(null, null, 5)', () => {
    expect(nKeysBetween(null, null, 5)).toEqual(['a0', 'a1', 'a2', 'a3', 'a4']);
  });

  it('(a4, null, 10)', () => {
    expect(nKeysBetween('a4', null, 10)).toEqual(['a5', 'a6', 'a7', 'a8', 'a9', 'aA', 'aB', 'aC', 'aD', 'aE']);
  });

  it('(null, a0, 5)', () => {
    expect(nKeysBetween(null, 'a0', 5)).toEqual(['Zv', 'Zw', 'Zx', 'Zy', 'Zz']);
  });

  it('(a0, a2, 20)', () => {
    expect(nKeysBetween('a0', 'a2', 20).join(' ')).toBe(
      'a04 a08 a0G a0K a0O a0V a0Z a0d a0l a0t a1 a14 a18 a1G a1O a1V a1Z a1d a1l a1t',
    );
  });

  it('yields strictly increasing keys for many counts', () => {
    for (const [a, b] of [
      [null, null],
      ['a0', null],
      [null, 'a0'],
      ['a0', 'a1'],
      ['Zz', 'a0'],
    ] as Array<[string | null, string | null]>) {
      for (const n of [2, 3, 7, 64, 300]) {
        const keys = nKeysBetween(a, b, n);
        expect(keys).toHaveLength(n);
        for (let i = 1; i < keys.length; i++) expect(compareRanks(keys[i - 1], keys[i])).toBeLessThan(0);
        if (a !== null) expect(compareRanks(a, keys[0])).toBeLessThan(0);
        if (b !== null) expect(compareRanks(keys[n - 1], b)).toBeLessThan(0);
      }
    }
  });

  it('rejects bad bounds like keyBetween', () => {
    expect(() => nKeysBetween('a1', 'a0', 3)).toThrow(RankError);
    expect(() => nKeysBetween('a00', null, 3)).toThrow(RankError);
  });
});

describe('validateRank', () => {
  it('accepts generated keys', () => {
    for (const key of ['a0', 'Zz', 'a0V', 'b127', 'Xzzz', 'A000000000000000000000000000V']) {
      expect(validateRank(key)).toEqual({ ok: true, rank: key });
    }
  });

  it('rejects what validateOrderKey rejects', () => {
    for (const key of ['', '0', 'a', 'a00', 'b1', 'A00000000000000000000000000', ' a0', '!0', 'a0!', 'a0é']) {
      const result = validateRank(key);
      expect(result.ok).toBe(false);
    }
  });

  it(`rejects keys longer than ${MAX_RANK_LENGTH} characters`, () => {
    const ok = 'a0' + 'V'.repeat(MAX_RANK_LENGTH - 2);
    expect(validateRank(ok).ok).toBe(true);
    const long = ok + 'V';
    expect(validateRank(long)).toEqual({ ok: false, reason: 'too_long' });
  });
});

describe('compareRanks', () => {
  it('is plain code unit (ASCII byte) order, not locale order', () => {
    expect(compareRanks('Zz', 'a0')).toBeLessThan(0);
    expect(compareRanks('a0', 'a0V')).toBeLessThan(0);
    expect(compareRanks('aZ', 'aa')).toBeLessThan(0);
    expect(compareRanks('a1', 'a1')).toBe(0);
    expect(compareRanks('b', 'a')).toBeGreaterThan(0);
  });

  it('agrees with the digit alphabet order', () => {
    const digits = BASE_62_DIGITS.split('');
    expect([...digits].sort(compareRanks)).toEqual(digits);
  });
});
