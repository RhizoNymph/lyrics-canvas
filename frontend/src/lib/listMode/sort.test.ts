import { describe, it, expect } from 'vitest';
import { LIST_SORTS, LIST_SORT_LABELS, compareForSort, parseListSort, sortSnippets } from './sort';
import { snippet } from '@/lib/merge/fixtures';

const ids = (list: { id: string }[]) => list.map((s) => s.id);

describe('LIST_SORTS', () => {
  it('lists the four sorts in menu order with labels', () => {
    expect(LIST_SORTS).toEqual(['last_edited', 'created', 'a_z', 'custom']);
    expect(LIST_SORTS.map((s) => LIST_SORT_LABELS[s])).toEqual(['Last edited', 'Created', 'A-Z', 'Custom']);
  });
});

describe('parseListSort', () => {
  it('accepts the four values and rejects anything else', () => {
    for (const s of LIST_SORTS) expect(parseListSort(s)).toBe(s);
    for (const bad of [null, '', 'Custom', 'az', 'last-edited']) expect(parseListSort(bad)).toBeNull();
  });
});

describe('last_edited', () => {
  it('is the existing list order (content_updated_at desc, created_at desc, id)', () => {
    const list = [
      snippet('b', 'x', { content_updated_at: '2026-10-01T10:00:00Z', created_at: '2026-01-02T00:00:00Z' }),
      snippet('a', 'x', { content_updated_at: '2026-10-01T10:00:00Z', created_at: '2026-01-02T00:00:00Z' }),
      snippet('c', 'x', { content_updated_at: '2026-10-02T10:00:00Z', created_at: '2026-01-01T00:00:00Z' }),
      snippet('d', 'x', { content_updated_at: '2026-10-01T10:00:00Z', created_at: '2026-01-03T00:00:00Z' }),
    ];
    expect(ids(sortSnippets('last_edited', list))).toEqual(['c', 'd', 'a', 'b']);
  });
});

describe('created', () => {
  it('is snippet created_at descending (as instants), then id, ignoring content edits', () => {
    const list = [
      snippet('old', 'x', { created_at: '2026-01-01T00:00:00Z', content_updated_at: '2026-12-01T00:00:00Z' }),
      snippet('new', 'x', { created_at: '2026-03-01T00:00:00Z', content_updated_at: '2026-03-01T00:00:00Z' }),
      // 2026-02-01T23:00Z, later than 2026-02-01T10:00Z although its string sorts earlier
      snippet('offset', 'x', { created_at: '2026-02-02T01:00:00+02:00' }),
      snippet('mid', 'x', { created_at: '2026-02-01T10:00:00Z' }),
      snippet('tie-b', 'x', { created_at: '2025-01-01T00:00:00Z' }),
      snippet('tie-a', 'x', { created_at: '2025-01-01T00:00:00Z' }),
      snippet('broken', 'x', { created_at: 'not a date' }),
    ];
    expect(ids(sortSnippets('created', list))).toEqual(['new', 'offset', 'mid', 'old', 'tie-a', 'tie-b', 'broken']);
  });
});

describe('a_z', () => {
  it('sorts by display text case-insensitively, then id', () => {
    const list = [
      snippet('1', 'banana'),
      snippet('2', 'Apple'),
      snippet('3', 'apple'),
      snippet('4', 'cherry'),
      snippet('0', 'APPLE'),
    ];
    expect(ids(sortSnippets('a_z', list))).toEqual(['0', '2', '3', '1', '4']);
  });

  it('ignores rest marks and leading whitespace (display text)', () => {
    const list = [snippet('r', '_ _ zebra'), snippet('s', '  ant'), snippet('t', 'moth')];
    expect(ids(sortSnippets('a_z', list))).toEqual(['s', 't', 'r']);
  });

  it('treats accents at base strength like their base letter', () => {
    const list = [snippet('2', 'eclair'), snippet('1', 'éclair'), snippet('3', 'dove')];
    expect(ids(sortSnippets('a_z', list))).toEqual(['3', '1', '2']);
  });
});

describe('custom', () => {
  it('is list_rank ascending in byte order, then placement_id', () => {
    const list = [
      snippet('a', 'x', { list_rank: 'a1', placement_id: 'p1' }),
      snippet('b', 'x', { list_rank: 'Zz', placement_id: 'p2' }),
      snippet('c', 'x', { list_rank: 'a0V', placement_id: 'p3' }),
      snippet('d', 'x', { list_rank: 'a0', placement_id: 'p9' }),
      snippet('e', 'x', { list_rank: 'a0', placement_id: 'p4' }),
      snippet('f', 'x', { list_rank: 'aa', placement_id: 'p5' }),
      snippet('g', 'x', { list_rank: 'aZ', placement_id: 'p6' }),
    ];
    expect(ids(sortSnippets('custom', list))).toEqual(['b', 'e', 'd', 'c', 'a', 'g', 'f']);
  });
});

describe('sortSnippets', () => {
  it('returns a copy and leaves the input untouched', () => {
    const list = [snippet('b', 'b'), snippet('a', 'a')];
    const sorted = sortSnippets('a_z', list);
    expect(ids(list)).toEqual(['b', 'a']);
    expect(sorted).not.toBe(list);
  });

  it('is a total order for every sort (antisymmetric on distinct snippets)', () => {
    const list = [
      snippet('a', 'same', { list_rank: 'a0', placement_id: 'p1', created_at: '2026-01-01T00:00:00Z' }),
      snippet('b', 'same', { list_rank: 'a0', placement_id: 'p2', created_at: '2026-01-01T00:00:00Z' }),
    ];
    for (const sort of LIST_SORTS) {
      const cmp = compareForSort(sort);
      expect(Math.sign(cmp(list[0], list[1]))).toBe(-Math.sign(cmp(list[1], list[0])));
      expect(cmp(list[0], list[1])).not.toBe(0);
    }
  });
});
