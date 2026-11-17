import { describe, it, expect } from 'vitest';
import { EMPTY_FILTER, filterReducer } from '@/lib/tagFilter';
import { rhymesBody } from './queries';

const filter = (text: string) => filterReducer(EMPTY_FILTER, { type: 'edit', text });

describe('rhymesBody order', () => {
  it('sends nothing for rhyme order (the default)', () => {
    expect(rhymesBody({ kind: 'word', wordId: 'w1' }, EMPTY_FILTER, 0.5, 'rhyme')).toEqual({ word_id: 'w1', min_score: 0.5 });
    expect(rhymesBody({ kind: 'word', wordId: 'w1' }, EMPTY_FILTER, 0.5)).toEqual({ word_id: 'w1', min_score: 0.5 });
  });

  it('adds order "meaning" next to the anchor and the filter', () => {
    expect(rhymesBody({ kind: 'word', wordId: 'w1' }, EMPTY_FILTER, 0.5, 'meaning')).toEqual({ word_id: 'w1', min_score: 0.5, order: 'meaning' });
    expect(rhymesBody({ kind: 'text', text: ' night ' }, filter('a | b'), 0.6, 'meaning')).toEqual({ text: 'night', min_score: 0.6, tag_filter: 'a | b', order: 'meaning' });
  });
});
