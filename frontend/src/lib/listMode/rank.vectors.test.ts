/**
 * Shared list rank vectors (spec/list_rank_vectors.json at the repo root, written by the backend branch and
 * byte-identical in every client). Read from that exact file; every entry must pass.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { RankError, keyBetween, nKeysBetween, validateRank } from './rank';

interface Vectors {
  between: { a: string | null; b: string | null; key: string }[];
  n_between: { a: string | null; b: string | null; n: number; keys: string[] }[];
  invalid: string[];
}

const VECTORS_URL = new URL('../../../../spec/list_rank_vectors.json', import.meta.url);
const vectors = JSON.parse(readFileSync(VECTORS_URL, 'utf8')) as Vectors;

describe('list rank vectors', () => {
  it('has every section, non-empty', () => {
    expect(vectors.between.length).toBeGreaterThan(0);
    expect(vectors.n_between.length).toBeGreaterThan(0);
    expect(vectors.invalid.length).toBeGreaterThan(0);
  });

  describe('between', () => {
    for (const v of vectors.between) {
      it(`(${JSON.stringify(v.a)}, ${JSON.stringify(v.b)}) -> ${v.key}`, () => {
        expect(keyBetween(v.a, v.b)).toBe(v.key);
        expect(validateRank(v.key).ok).toBe(v.key.length <= 64);
      });
    }
  });

  describe('n_between', () => {
    for (const v of vectors.n_between) {
      it(`(${JSON.stringify(v.a)}, ${JSON.stringify(v.b)}, ${v.n})`, () => {
        expect(nKeysBetween(v.a, v.b, v.n)).toEqual(v.keys);
      });
    }
  });

  describe('invalid', () => {
    for (const key of vectors.invalid) {
      it(JSON.stringify(key), () => {
        expect(validateRank(key).ok).toBe(false);
        if (key.length <= 64) {
          expect(() => keyBetween(key, null)).toThrow(RankError);
          expect(() => keyBetween(null, key)).toThrow(RankError);
        }
      });
    }
  });
});
