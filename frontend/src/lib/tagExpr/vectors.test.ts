/**
 * Shared conformance vectors (spec/tag_expression_vectors.json at the repo root, byte-identical in the
 * backend, web and Android branches). Read from that exact file; never copied.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { evaluate, format, parse, renameTag } from './index';
import type { SnippetType } from '@/types';

type Segment = string | { repeat: string; times: number };
type Expandable = string | Segment[];

interface Vectors {
  version: number;
  parse_ok: { input: Expandable; normalized: Expandable }[];
  parse_error: { input: Expandable; code: string; position: number }[];
  eval: { expression: string; snippet: { type: SnippetType | null; tags: string[] }; matches: boolean }[];
  rename: { expression: string; from: string; to: string; result: string }[];
}

const VECTORS_URL = new URL('../../../../spec/tag_expression_vectors.json', import.meta.url);
const vectors = JSON.parse(readFileSync(VECTORS_URL, 'utf8')) as Vectors;

function expand(v: Expandable): string {
  if (typeof v === 'string') return v;
  return v.map((s) => (typeof s === 'string' ? s : s.repeat.repeat(s.times))).join('');
}

function label(s: string): string {
  return JSON.stringify(s.length > 60 ? `${s.slice(0, 60)}…` : s);
}

describe('tag expression vectors', () => {
  it('is version 2 and non-empty', () => {
    expect(vectors.version).toBe(2);
    expect(vectors.parse_ok.length).toBeGreaterThan(0);
    expect(vectors.parse_error.length).toBeGreaterThan(0);
    expect(vectors.eval.length).toBeGreaterThan(0);
    expect(vectors.rename.length).toBeGreaterThan(0);
  });

  it('has typeless (word bank) eval subjects, which run with the other eval cases', () => {
    expect(vectors.eval.filter((v) => v.snippet.type === null).length).toBeGreaterThanOrEqual(8);
  });

  describe('parse_ok', () => {
    it.each(vectors.parse_ok.map((v) => [label(expand(v.input)), v] as const))('%s', (_, v) => {
      const parsed = parse(expand(v.input));
      if (!parsed.ok) throw new Error(`unexpected ${parsed.error.code} at ${parsed.error.position}`);
      const normalized = format(parsed.expr);
      expect(normalized).toBe(expand(v.normalized));
      const again = parse(normalized);
      expect(again.ok && format(again.expr)).toBe(normalized);
    });
  });

  describe('parse_error', () => {
    it.each(vectors.parse_error.map((v) => [label(expand(v.input)), v] as const))('%s', (_, v) => {
      const parsed = parse(expand(v.input));
      expect(parsed.ok).toBe(false);
      if (parsed.ok) return;
      expect({ code: parsed.error.code, position: parsed.error.position }).toEqual({ code: v.code, position: v.position });
      expect(parsed.error.message.length).toBeGreaterThan(0);
    });
  });

  describe('eval', () => {
    it.each(vectors.eval.map((v) => [`${label(v.expression)} on ${JSON.stringify(v.snippet)}`, v] as const))('%s', (_, v) => {
      const parsed = parse(v.expression);
      if (!parsed.ok) throw new Error(`unexpected ${parsed.error.code}`);
      expect(evaluate(parsed.expr, { type: v.snippet.type, tagNames: v.snippet.tags })).toBe(v.matches);
    });
  });

  describe('rename', () => {
    it.each(vectors.rename.map((v) => [`${label(v.expression)} ${v.from} -> ${v.to}`, v] as const))('%s', (_, v) => {
      const parsed = parse(v.expression);
      if (!parsed.ok) throw new Error(`unexpected ${parsed.error.code}`);
      expect(format(renameTag(parsed.expr, v.from, v.to))).toBe(v.result);
    });
  });
});
