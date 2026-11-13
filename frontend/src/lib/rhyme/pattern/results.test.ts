import { describe, it, expect } from 'vitest';
import {
  appendLineMatches, appendSlotPage, canLoadMoreLines, canLoadMoreSlot, dismissFromResults, firstPageResults, lineMatchWords,
  serverSlotsOf, slotsHitLabel,
} from './results';
import type { PatternSuggestResponse } from '@/types/rhymePattern';
import { pcand, phit, pmatch, pslot } from './fixtures';

function response(over: Partial<PatternSuggestResponse> = {}): PatternSuggestResponse {
  return {
    slots: [
      { slot: pslot('s0', [[0, 0, 1], [1, 0, 1]]), candidates: [pcand('dict:a', 'ready', 0.9), pcand('dict:b', 'heavy', 0.8)], total: 50 },
      { slot: pslot('s1', [[0, 3, 4], [1, 3, 4]]), candidates: [pcand('dict:c', 'strong', 0.7)], total: 1 },
    ],
    line_matches: [pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready'), phit('s1', 2, 3, 'strong')])],
    line_match_total: 31,
    elapsed_ms: 3,
    ...over,
  };
}

describe('firstPageResults', () => {
  it('keys a column per slot, drops dismissed candidates, and keeps the line matches', () => {
    const r = firstPageResults(response(), new Set(['dict:b']));
    expect([...r.columns.keys()]).toEqual(['s0', 's1']);
    expect(r.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:a']);
    expect(r.columns.get('s0')).toMatchObject({ total: 50, offset: 0 });
    expect(r.lineMatches).toHaveLength(1);
    expect(r.lineMatchTotal).toBe(31);
    expect(r.lineMatchOffset).toBe(0);
    expect(serverSlotsOf(r).get('s1')?.id).toBe('s1');
  });

  it('drops dismissed line matches too', () => {
    expect(firstPageResults(response(), new Set(['note:n:0:0'])).lineMatches).toEqual([]);
  });
});

describe('appendSlotPage', () => {
  it('appends only that slot, without duplicates or dismissed ids, and advances its offset', () => {
    const first = firstPageResults(response(), new Set());
    const page = response({
      slots: [
        { slot: pslot('s0', [[0, 0, 1], [1, 0, 1]]), candidates: [pcand('dict:a', 'ready', 0.9), pcand('dict:d', 'steady', 0.6), pcand('dict:x', 'x', 0.6)], total: 52 },
        { slot: pslot('s1', [[0, 3, 4], [1, 3, 4]]), candidates: [pcand('dict:zz', 'zz', 0.7)], total: 9 },
      ],
    });
    const next = appendSlotPage(first, 's0', 30, page, new Set(['dict:x']));
    expect(next.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:a', 'dict:b', 'dict:d']);
    expect(next.columns.get('s0')).toMatchObject({ total: 52, offset: 30 });
    expect(next.columns.get('s1')).toBe(first.columns.get('s1'));
    expect(next.lineMatches).toBe(first.lineMatches);
  });

  it('is unchanged when the slot is missing from either side', () => {
    const first = firstPageResults(response(), new Set());
    expect(appendSlotPage(first, 'nope', 30, response(), new Set())).toBe(first);
  });
});

describe('appendLineMatches', () => {
  it('appends unseen line matches and advances the offset', () => {
    const first = firstPageResults(response(), new Set());
    const more = response({
      line_matches: [
        pmatch('note:n:0:0', 'ready and strong', []),
        pmatch('note:n:1:0', 'steady and long', [phit('s0', 0, 1, 'steady'), phit('s1', 2, 3, 'long')]),
      ],
      line_match_total: 32,
    });
    const next = appendLineMatches(first, 30, more, new Set());
    expect(next.lineMatches.map((m) => m.unit.id)).toEqual(['note:n:0:0', 'note:n:1:0']);
    expect(next).toMatchObject({ lineMatchOffset: 30, lineMatchTotal: 32 });
    expect(next.columns).toBe(first.columns);
  });
});

describe('dismissFromResults', () => {
  it('removes the id from every column and the line matches', () => {
    const r = dismissFromResults(firstPageResults(response(), new Set()), 'dict:a');
    expect(r.columns.get('s0')?.candidates.map((c) => c.id)).toEqual(['dict:b']);
    expect(dismissFromResults(r, 'note:n:0:0').lineMatches).toEqual([]);
  });
});

describe('paging predicates', () => {
  it('allows More while offset + page size is under the total', () => {
    const r = firstPageResults(response(), new Set());
    expect(canLoadMoreSlot(r.columns.get('s0')!)).toBe(true);
    expect(canLoadMoreSlot(r.columns.get('s1')!)).toBe(false);
    expect(canLoadMoreLines(r)).toBe(true);
    expect(canLoadMoreLines({ ...r, lineMatchTotal: 30 })).toBe(false);
  });
});

describe('lineMatchWords', () => {
  it('marks the hit words with their slot index', () => {
    const match = pmatch('note:n:0:0', 'ready and the strong', [phit('s0', 0, 1, 'ready'), phit('s1', 2, 4, 'the strong')]);
    const index = (id: string) => (id === 's0' ? 0 : id === 's1' ? 1 : null);
    expect(lineMatchWords(match, index)).toEqual([
      { text: 'ready', slotIndex: 0 },
      { text: 'and', slotIndex: null },
      { text: 'the', slotIndex: 1 },
      { text: 'strong', slotIndex: 1 },
    ]);
  });

  it('ignores hits of slots no longer shown', () => {
    const match = pmatch('note:n:0:0', 'ready now', [phit('gone', 0, 1, 'ready')]);
    expect(lineMatchWords(match, () => null)).toEqual([
      { text: 'ready', slotIndex: null },
      { text: 'now', slotIndex: null },
    ]);
  });

  it('labels the hit count', () => {
    expect(slotsHitLabel(pmatch('x', 'a b', [phit('s0', 0, 1, 'a'), phit('s1', 1, 2, 'b')]), 3)).toBe('2 of 3 slots');
  });
});
