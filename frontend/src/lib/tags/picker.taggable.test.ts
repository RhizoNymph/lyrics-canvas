import { describe, it, expect } from 'vitest';
import { checkState, planTagToggle } from './picker';

describe('planTagToggle on word-like targets', () => {
  const w = (id: string, tagIds: string[]) => ({ id, tags: tagIds.map((t) => ({ id: t })) });

  it('unlinks from all when every target has the tag', () => {
    expect(planTagToggle('t1', [w('a', ['t1']), w('b', ['t1', 't2'])])).toEqual({ action: 'unlink', tagId: 't1', targetIds: ['a', 'b'] });
  });

  it('links only where missing for a mixed or unchecked tag', () => {
    expect(planTagToggle('t1', [w('a', ['t1']), w('b', [])])).toEqual({ action: 'link', tagId: 't1', targetIds: ['b'] });
    expect(planTagToggle('t9', [w('a', []), w('b', [])])).toEqual({ action: 'link', tagId: 't9', targetIds: ['a', 'b'] });
  });

  it('agrees with checkState', () => {
    expect(checkState('t1', [w('a', ['t1']), w('b', [])])).toBe('some');
  });
});
