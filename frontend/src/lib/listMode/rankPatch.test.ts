import { describe, it, expect } from 'vitest';
import { patchRanks } from './rankPatch';
import { snippet } from '@/lib/merge/fixtures';

const map = () =>
  new Map([
    ['a', snippet('a', 'a', { list_rank: 'a0' })],
    ['b', snippet('b', 'b', { list_rank: 'a1' })],
  ]);

describe('patchRanks', () => {
  it('sets list_rank on the listed snippets and leaves the rest as the same objects', () => {
    const before = map();
    const after = patchRanks(before, [{ id: 'a', list_rank: 'a0V' }]);
    expect(after).not.toBe(before);
    expect(after.get('a')!.list_rank).toBe('a0V');
    expect(after.get('b')).toBe(before.get('b'));
    expect(before.get('a')!.list_rank).toBe('a0');
  });

  it('ignores ids that are not in the map', () => {
    const before = map();
    expect(patchRanks(before, [{ id: 'zz', list_rank: 'a5' }])).toBe(before);
  });

  it('returns the same map when nothing changes', () => {
    const before = map();
    expect(patchRanks(before, [{ id: 'a', list_rank: 'a0' }])).toBe(before);
    expect(patchRanks(before, [])).toBe(before);
  });

  it('with `expect`, only patches a snippet whose rank is still the expected one', () => {
    const before = map();
    const after = patchRanks(before, [
      { id: 'a', list_rank: 'Zz', expect: 'a0' },
      { id: 'b', list_rank: 'Zy', expect: 'a9' },
    ]);
    expect(after.get('a')!.list_rank).toBe('Zz');
    expect(after.get('b')!.list_rank).toBe('a1');
  });
});
