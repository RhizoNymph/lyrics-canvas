import { describe, it, expect } from 'vitest';
import { lineMatchDerivedFrom, patternSpanId } from './derived';
import { phit, pmatch, pslot, pspan } from './fixtures';

describe('patternSpanId', () => {
  it('formats a member id', () => {
    expect(patternSpanId('sn', { line_index: 3, word_start: 1, word_end: 3 })).toBe('pattern:sn:3:1-3');
  });
});

describe('lineMatchDerivedFrom', () => {
  it('anchors on the first hit slot last member', () => {
    const slot = { ...pslot('s0', [], { k: 3 }), members: [pspan(0, 0, 1, 'ready'), pspan(1, 2, 3, 'steady')] };
    const match = pmatch('note:n:4:0', 'heavy and long', [phit('s0', 0, 1, 'heavy'), phit('s1', 2, 3, 'long')], 0.81);
    expect(lineMatchDerivedFrom(match, new Map([['s0', slot]]), 'sn')).toEqual({
      provenance: 'note',
      source_unit_id: 'note:n:4:0',
      score: 0.81,
      match_depth: 3,
      k: 3,
      anchor_unit_id: 'pattern:sn:1:2-3',
      anchor_text: 'steady',
    });
  });

  it('falls back when the slot is unknown', () => {
    const match = pmatch('note:n:4:0', 'x y', [phit('gone', 0, 1, 'x')]);
    expect(lineMatchDerivedFrom(match, new Map(), 'sn')).toMatchObject({ k: 1, match_depth: 1, anchor_unit_id: 'pattern:sn', anchor_text: '' });
  });
});
