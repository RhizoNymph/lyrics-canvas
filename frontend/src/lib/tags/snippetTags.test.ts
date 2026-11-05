import { describe, it, expect } from 'vitest';
import { embedTag, patchEmbeddedTag, removeEmbeddedTag, setEmbeddedTag } from './snippetTags';
import { snippet, tag } from '@/lib/merge/fixtures';

const a = snippet('a', 'x', { tags: [tag('t1', 'love'), tag('t2', 'night')] });
const b = snippet('b', 'y', { tags: [tag('t2', 'night')] });
const c = snippet('c', 'z');
const all = () => new Map([a, b, c].map((s) => [s.id, s]));

describe('embedTag', () => {
  it('keeps only id, name and color', () => {
    expect(embedTag({ id: 'x', name: 'n', color: '#000000', snippet_count: 3, word_count: 0, created_at: 'now' })).toEqual({ id: 'x', name: 'n', color: '#000000' });
  });
});

describe('patchEmbeddedTag', () => {
  it('replaces the tag in place in every snippet carrying it', () => {
    const next = patchEmbeddedTag(all(), { id: 't2', name: 'midnight', color: '#111111' });
    expect(next.get('a')?.tags).toEqual([tag('t1', 'love'), { id: 't2', name: 'midnight', color: '#111111' }]);
    expect(next.get('b')?.tags).toEqual([{ id: 't2', name: 'midnight', color: '#111111' }]);
    expect(next.get('c')).toBe(c);
  });

  it('returns the same map when nothing carries the tag', () => {
    const m = all();
    expect(patchEmbeddedTag(m, { id: 'zz', name: 'q', color: '#000000' })).toBe(m);
  });
});

describe('removeEmbeddedTag', () => {
  it('drops the tag everywhere', () => {
    const next = removeEmbeddedTag(all(), 't2');
    expect(next.get('a')?.tags.map((t) => t.id)).toEqual(['t1']);
    expect(next.get('b')?.tags).toEqual([]);
  });
});

describe('setEmbeddedTag', () => {
  const t1 = tag('t1', 'love');

  it('links by appending where missing, idempotently', () => {
    const next = setEmbeddedTag(all(), ['a', 'b', 'c'], t1, true);
    expect(next.get('a')).toBe(a);
    expect(next.get('b')?.tags.map((t) => t.id)).toEqual(['t2', 't1']);
    expect(next.get('c')?.tags.map((t) => t.id)).toEqual(['t1']);
  });

  it('unlinks only on the given snippets', () => {
    const next = setEmbeddedTag(all(), ['b'], tag('t2'), false);
    expect(next.get('b')?.tags).toEqual([]);
    expect(next.get('a')?.tags.map((t) => t.id)).toEqual(['t1', 't2']);
  });
});
