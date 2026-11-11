import { describe, it, expect } from 'vitest';
import { filterCountLabels } from './counts';

describe('filterCountLabels', () => {
  it('shows the total and no search count when nothing filters', () => {
    expect(filterCountLabels({ total: 12, shown: 12, tagActive: false, searchActive: false })).toEqual({ tag: '12 snippets', search: null });
  });

  it('counts what both filters leave, in the tag bar and the search box', () => {
    expect(filterCountLabels({ total: 12, shown: 3, tagActive: true, searchActive: true })).toEqual({ tag: '3 of 12 shown', search: '3 of 12' });
    expect(filterCountLabels({ total: 12, shown: 5, tagActive: false, searchActive: true })).toEqual({ tag: '5 of 12 shown', search: '5 of 12' });
    expect(filterCountLabels({ total: 12, shown: 5, tagActive: true, searchActive: false })).toEqual({ tag: '5 of 12 shown', search: null });
  });
});
