import { describe, it, expect } from 'vitest';
import { checkState, createOffer, pickerTargets, planToggle, searchTags } from './picker';
import { snippet, tag } from '@/lib/merge/fixtures';
import type { Tag } from '@/types';

const userTag = (id: string, name: string): Tag => ({ id, name, color: '#123456', snippet_count: 0, word_count: 0, created_at: '' });

const a = snippet('a', 'x', { tags: [tag('t1', 'love')] });
const b = snippet('b', 'y', { tags: [tag('t1', 'love'), tag('t2', 'night')] });
const c = snippet('c', 'z');
const all = new Map([a, b, c].map((s) => [s.id, s]));

describe('pickerTargets', () => {
  it('uses the multi-selection when opened on one of its members', () => {
    expect(pickerTargets('a', new Set(['a', 'b']), all).map((s) => s.id).sort()).toEqual(['a', 'b']);
  });

  it('uses only the context snippet when it is outside the selection or the selection is single', () => {
    expect(pickerTargets('c', new Set(['a', 'b']), all).map((s) => s.id)).toEqual(['c']);
    expect(pickerTargets('a', new Set(['a']), all).map((s) => s.id)).toEqual(['a']);
    expect(pickerTargets('a', new Set(), all).map((s) => s.id)).toEqual(['a']);
  });

  it('drops snippets that no longer exist', () => {
    expect(pickerTargets('a', new Set(['a', 'gone']), all).map((s) => s.id)).toEqual(['a']);
    expect(pickerTargets('gone', new Set(), all)).toEqual([]);
  });
});

describe('checkState', () => {
  it('is all / some / none across the targets', () => {
    expect(checkState('t1', [a, b])).toBe('all');
    expect(checkState('t2', [a, b])).toBe('some');
    expect(checkState('t2', [a, c])).toBe('none');
    expect(checkState('t1', [])).toBe('none');
  });
});

describe('planToggle', () => {
  it('unlinks from every target when all carry the tag', () => {
    expect(planToggle('t1', [a, b])).toEqual({ action: 'unlink', tagId: 't1', snippetIds: ['a', 'b'] });
  });

  it('links only where missing when mixed', () => {
    expect(planToggle('t2', [a, b, c])).toEqual({ action: 'link', tagId: 't2', snippetIds: ['a', 'c'] });
  });

  it('links everywhere when none carry it', () => {
    expect(planToggle('t9', [a, c])).toEqual({ action: 'link', tagId: 't9', snippetIds: ['a', 'c'] });
  });
});

describe('searchTags', () => {
  const tags = [userTag('1', 'glove'), userTag('2', 'Love'), userTag('3', 'night')];

  it('returns everything for an empty query', () => {
    expect(searchTags(tags, '  ').map((t) => t.id)).toEqual(['1', '2', '3']);
  });

  it('ranks prefix matches first, case-insensitively', () => {
    expect(searchTags(tags, 'LO').map((t) => t.name)).toEqual(['Love', 'glove']);
    expect(searchTags(tags, 'zzz')).toEqual([]);
  });
});

describe('createOffer', () => {
  const tags = [userTag('1', 'Love')];

  it('offers a trimmed new name', () => {
    expect(createOffer('  late night ', tags)).toEqual({ kind: 'create', name: 'late night' });
  });

  it('does not offer an existing name (case-insensitive) or an empty query', () => {
    expect(createOffer('love', tags)).toEqual({ kind: 'none' });
    expect(createOffer('   ', tags)).toEqual({ kind: 'none' });
  });

  it('reports names the server would reject', () => {
    expect(createOffer('x'.repeat(101), tags)).toEqual({ kind: 'invalid', problem: 'too_long' });
    expect(createOffer('a\u0007b', tags)).toEqual({ kind: 'invalid', problem: 'control_character' });
  });
});
