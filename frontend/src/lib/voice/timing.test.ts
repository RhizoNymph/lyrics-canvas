import { describe, it, expect } from 'vitest';
import { SEEK_STEP_MS, clampSeekMs, wordIndexAt } from './timing';
import type { TimedWord } from './types';

const w = (start_ms: number, end_ms: number): TimedWord => ({ text: 'x', start_ms, end_ms, probability: null });
const words = [w(0, 400), w(450, 900), w(900, 1200), w(1500, 1900)];

describe('wordIndexAt', () => {
  it('is null for no words', () => {
    expect(wordIndexAt([], 100)).toBeNull();
  });

  it('finds the word whose [start, end) contains the time', () => {
    expect(wordIndexAt(words, 0)).toBe(0);
    expect(wordIndexAt(words, 399)).toBe(0);
    expect(wordIndexAt(words, 450)).toBe(1);
    expect(wordIndexAt(words, 1899)).toBe(3);
  });

  it('switches to the next word exactly at a shared boundary', () => {
    expect(wordIndexAt(words, 899)).toBe(1);
    expect(wordIndexAt(words, 900)).toBe(2);
  });

  it('is null in a gap, before the first and after the last word', () => {
    expect(wordIndexAt(words, 420)).toBeNull();
    expect(wordIndexAt(words, 1300)).toBeNull();
    expect(wordIndexAt(words, -5)).toBeNull();
    expect(wordIndexAt(words, 1900)).toBeNull();
    expect(wordIndexAt(words, 99_999)).toBeNull();
  });

  it('is null for non-finite times', () => {
    expect(wordIndexAt(words, Number.NaN)).toBeNull();
    expect(wordIndexAt(words, Number.POSITIVE_INFINITY)).toBeNull();
  });

  it('prefers the later word when two overlap', () => {
    expect(wordIndexAt([w(0, 500), w(400, 800)], 450)).toBe(1);
  });

  it('highlights a zero-length word at its instant only through the next word', () => {
    expect(wordIndexAt([w(100, 100), w(100, 300)], 100)).toBe(1);
  });

  it('agrees with a linear scan on many points', () => {
    const many = Array.from({ length: 50 }, (_, i) => w(i * 100, i * 100 + 70));
    for (let t = -10; t < 5100; t += 7) {
      let expected: number | null = null;
      many.forEach((x, i) => {
        if (x.start_ms <= t && t < x.end_ms) expected = i;
      });
      expect(wordIndexAt(many, t)).toBe(expected);
    }
  });
});

describe('clampSeekMs', () => {
  it('steps 3 s per the contract and clamps to [0, duration]', () => {
    expect(SEEK_STEP_MS).toBe(3000);
    expect(clampSeekMs(5000, SEEK_STEP_MS, 10_000)).toBe(8000);
    expect(clampSeekMs(5000, -SEEK_STEP_MS, 10_000)).toBe(2000);
    expect(clampSeekMs(1000, -SEEK_STEP_MS, 10_000)).toBe(0);
    expect(clampSeekMs(9000, SEEK_STEP_MS, 10_000)).toBe(10_000);
  });

  it('treats a non-finite duration as unbounded above and a non-finite position as 0', () => {
    expect(clampSeekMs(9000, SEEK_STEP_MS, Number.POSITIVE_INFINITY)).toBe(12_000);
    expect(clampSeekMs(Number.NaN, SEEK_STEP_MS, 10_000)).toBe(3000);
  });
});
