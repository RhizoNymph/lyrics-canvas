import { describe, it, expect } from 'vitest';
import { compareForList, sortForList, applyFrozenOrder } from './order';
import { snippet } from '@/lib/merge/fixtures';

const at = (id: string, content: string, created = '2026-01-01T00:00:00Z') =>
  snippet(id, id, { content_updated_at: content, created_at: created });

describe('sortForList', () => {
  it('puts the most recently content-edited snippet first', () => {
    const list = [
      at('old', '2026-10-01T10:00:00Z'),
      at('new', '2026-10-03T10:00:00Z'),
      at('mid', '2026-10-02T10:00:00Z'),
    ];
    expect(sortForList(list).map((s) => s.id)).toEqual(['new', 'mid', 'old']);
  });

  it('compares instants, not strings, across offsets and fractional seconds', () => {
    const list = [
      at('utc', '2026-10-01T10:00:00Z'),
      // 09:30 UTC: earlier than 10:00 UTC although its string sorts later
      at('offset', '2026-10-01T11:30:00+02:00'),
      at('micro', '2026-10-01T10:00:00.500000+00:00'),
    ];
    expect(sortForList(list).map((s) => s.id)).toEqual(['micro', 'utc', 'offset']);
  });

  it('breaks content ties by created_at descending, then id ascending', () => {
    const t = '2026-10-01T10:00:00Z';
    const list = [
      at('b', t, '2026-01-01T00:00:00Z'),
      at('a', t, '2026-01-01T00:00:00Z'),
      at('c', t, '2026-02-01T00:00:00Z'),
    ];
    expect(sortForList(list).map((s) => s.id)).toEqual(['c', 'a', 'b']);
  });

  it('sorts unparseable or missing timestamps last, deterministically', () => {
    const missing = snippet('missing', 'm', { created_at: '' });
    delete (missing as Partial<typeof missing>).content_updated_at;
    const list = [missing, at('bad', 'not a date'), at('ok', '2026-10-01T10:00:00Z')];
    expect(sortForList(list).map((s) => s.id)).toEqual(['ok', 'bad', 'missing']);
  });

  it('does not mutate its input', () => {
    const list = [at('x', '2026-10-01T10:00:00Z'), at('y', '2026-10-02T10:00:00Z')];
    sortForList(list);
    expect(list.map((s) => s.id)).toEqual(['x', 'y']);
  });

  it('is a total order (antisymmetric) for the comparator', () => {
    const p = at('p', '2026-10-01T10:00:00Z');
    const q = at('q', '2026-10-01T10:00:00Z');
    expect(compareForList(p, q)).toBe(-compareForList(q, p));
    expect(compareForList(p, p)).toBe(0);
  });
});

describe('applyFrozenOrder', () => {
  it('returns the sorted order when not frozen', () => {
    expect(applyFrozenOrder(['a', 'b', 'c'], null)).toEqual(['a', 'b', 'c']);
  });

  it('keeps the frozen positions while frozen even if the sort changed', () => {
    expect(applyFrozenOrder(['c', 'a', 'b'], ['a', 'b', 'c'])).toEqual(['a', 'b', 'c']);
  });

  it('drops ids that are gone', () => {
    expect(applyFrozenOrder(['a', 'c'], ['a', 'b', 'c'])).toEqual(['a', 'c']);
  });

  it('puts ids new since the freeze on top in sorted order', () => {
    expect(applyFrozenOrder(['n2', 'b', 'n1', 'a'], ['a', 'b'])).toEqual(['n2', 'n1', 'a', 'b']);
  });
});
