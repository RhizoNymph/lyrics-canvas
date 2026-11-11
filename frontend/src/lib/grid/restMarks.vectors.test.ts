/**
 * Shared rest mark vectors (spec/rest_mark_vectors.json at the repo root, byte-identical to the backend's
 * backend/tests/fixtures/rest_mark_vectors.json and the Android copy). Read from that exact file.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { displayText, tokenizeLine } from './restMarks';
import { rebuildRawText } from './rawText';
import { matchesSearch, parseSearchQuery } from '@/lib/search/match';
import type { GridCell } from '@/types';

type VectorToken = { word: string } | { rest: true };

interface Vectors {
  version: number;
  tokenize: { line: string; tokens: VectorToken[] }[];
  word_indexes: { line: string; word_index_per_token: (number | null)[] }[];
  rebuild: { cells: GridCell[]; text: string }[];
  display: { text: string; display: string }[];
  match: { query: string; text: string; matches: boolean }[];
}

const VECTORS_URL = new URL('../../../../spec/rest_mark_vectors.json', import.meta.url);
const vectors = JSON.parse(readFileSync(VECTORS_URL, 'utf8')) as Vectors;

describe('rest mark vectors', () => {
  it('is version 1 and every section is non-empty', () => {
    expect(vectors.version).toBe(1);
    expect(vectors.tokenize.length).toBeGreaterThan(0);
    expect(vectors.word_indexes.length).toBeGreaterThan(0);
    expect(vectors.rebuild.length).toBeGreaterThan(0);
    expect(vectors.display.length).toBeGreaterThan(0);
    expect(vectors.match.length).toBeGreaterThan(0);
  });

  describe('tokenize', () => {
    for (const v of vectors.tokenize) {
      it(JSON.stringify(v.line), () => {
        const tokens = tokenizeLine(v.line).map((t): VectorToken => (t.kind === 'rest' ? { rest: true } : { word: t.text }));
        expect(tokens).toEqual(v.tokens);
      });
    }
  });

  describe('word indexes', () => {
    for (const v of vectors.word_indexes) {
      it(JSON.stringify(v.line), () => {
        expect(tokenizeLine(v.line).map((t) => (t.kind === 'word' ? t.wordIndex : null))).toEqual(v.word_index_per_token);
      });
    }
  });

  describe('rebuild', () => {
    for (const v of vectors.rebuild) {
      it(JSON.stringify(v.text), () => {
        const syllables = v.cells.filter((c) => !c.rest).length;
        expect(rebuildRawText({ cells: v.cells, syllable_count: syllables, raw_text: 'stale' })).toBe(v.text);
      });
    }
  });

  describe('display', () => {
    for (const v of vectors.display) {
      it(JSON.stringify(v.text), () => {
        expect(displayText(v.text)).toBe(v.display);
      });
    }
  });

  describe('match', () => {
    for (const v of vectors.match) {
      it(`${JSON.stringify(v.query)} in ${JSON.stringify(v.text)}`, () => {
        expect(matchesSearch(parseSearchQuery(v.query), v.text)).toBe(v.matches);
      });
    }
  });

  describe('syllabify (localSyllabifyLine) follows the tokenizer', () => {
    for (const v of vectors.tokenize) {
      it(JSON.stringify(v.line), async () => {
        const { localSyllabifyLine } = await import('./localSyllabify');
        const line = localSyllabifyLine(v.line);
        const restCount = v.tokens.filter((t) => 'rest' in t).length;
        expect(line.cells.filter((c) => c.rest).length).toBe(restCount);
        for (const cell of line.cells) if (cell.rest) expect(cell).toEqual({ rest: true });
        expect(line.syllable_count).toBe(line.cells.length - restCount);
        expect(line.raw_text).toBe(v.line.trim());
      });
    }
  });
});
