import { describe, it, expect } from 'vitest';
import { bw } from './fixtures';
import { DEFAULT_DRAFT_TYPE, addSummary, draftFromWords, isDraftCreatable, selectedFieldText } from './draft';

describe('draftFromWords', () => {
  it('puts one word per line in the given order with the freestyle default', () => {
    expect(draftFromWords([bw('a', 'night'), bw('b', 'neon lights')])).toEqual({ text: 'night\nneon lights', type: 'freestyle' });
    expect(DEFAULT_DRAFT_TYPE).toBe('freestyle');
  });
});

describe('isDraftCreatable', () => {
  it('needs some non-blank text', () => {
    expect(isDraftCreatable('a')).toBe(true);
    expect(isDraftCreatable(' \n ')).toBe(false);
  });
});

describe('addSummary', () => {
  it('reports created and existing counts', () => {
    expect(addSummary({ created: [bw('a', 'x'), bw('b', 'y')], existing: [bw('c', 'z')] })).toBe('Added 2, 1 already in bank');
    expect(addSummary({ created: [], existing: [] })).toBe('Added 0, 0 already in bank');
  });
});

describe('selectedFieldText', () => {
  it('slices the selection whichever way it was made', () => {
    expect(selectedFieldText('neon lights tonight', 5, 11)).toBe('lights');
    expect(selectedFieldText('neon lights tonight', 11, 5)).toBe('lights');
  });

  it('is empty without a selection', () => {
    expect(selectedFieldText('abc', 1, 1)).toBe('');
    expect(selectedFieldText('abc', null, null)).toBe('');
  });
});
