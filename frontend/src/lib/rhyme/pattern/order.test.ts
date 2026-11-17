import { describe, it, expect } from 'vitest';
import { buildSuggestRequest } from './request';
import type { PatternParams } from './request';
import { appendLineMatches, appendSlotPage, dismissFromResults, firstPageResults } from './results';
import type { PatternSession, SlotEditState } from './types';
import type { PatternSuggestResponse } from '@/types/rhymePattern';
import { pcand, phit, pmatch, pslot } from './fixtures';

const session: PatternSession = { canvasId: 'c1', snippetId: 'sn', lineStart: 0, lineEnd: 2 };
const edit: SlotEditState = {
  lines: [{ line: 0, wordCount: 3 }, { line: 1, wordCount: 3 }],
  slots: [{ id: 's0', on: true, members: [{ line: 0, start: 0, end: 1 }, { line: 1, start: 0, end: 1 }] }],
  nextUserId: 0,
};
const base: PatternParams = { minScore: 0.5, sources: ['dictionary'], includeRare: false, tagFilter: null, excludeIds: [] };

function response(over: Partial<PatternSuggestResponse> = {}): PatternSuggestResponse {
  return {
    slots: [{ slot: pslot('s0', [[0, 0, 1], [1, 0, 1]]), candidates: [pcand('dict:a', 'ready', 0.96, { band: 'perfect', similarity: 0.4 })], total: 50 }],
    line_matches: [pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready')])],
    line_match_total: 31,
    elapsed_ms: 3,
    ...over,
  };
}

describe('buildSuggestRequest order', () => {
  it('leaves the field out without an order or in rhyme order', () => {
    expect('order' in (buildSuggestRequest(session, edit, base, { kind: 'first' }) ?? {})).toBe(false);
    expect('order' in (buildSuggestRequest(session, edit, { ...base, order: 'rhyme' }, { kind: 'first' }) ?? {})).toBe(false);
  });

  it('sends order "meaning" on every kind of page', () => {
    const p = { ...base, order: 'meaning' as const };
    expect(buildSuggestRequest(session, edit, p, { kind: 'first' })?.order).toBe('meaning');
    expect(buildSuggestRequest(session, edit, p, { kind: 'slot_more', slotId: 's0', offset: 30 })?.order).toBe('meaning');
    expect(buildSuggestRequest(session, edit, p, { kind: 'line_more', offset: 30 })?.order).toBe('meaning');
  });
});

describe('results ordering', () => {
  it('defaults to rhyme asked and given', () => {
    expect(firstPageResults(response(), new Set()).ordering).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });

  it('keeps the requested and applied order of the first page', () => {
    expect(firstPageResults(response({ order_applied: 'meaning' }), new Set(), 'meaning').ordering).toEqual({ requested: 'meaning', applied: 'meaning' });
    expect(firstPageResults(response({ order_applied: 'rhyme' }), new Set(), 'meaning').ordering).toEqual({ requested: 'meaning', applied: 'rhyme' });
    expect(firstPageResults(response(), new Set(), 'meaning').ordering).toEqual({ requested: 'meaning', applied: 'rhyme' });
  });

  it('keeps band and similarity on the candidates', () => {
    const c = firstPageResults(response({ order_applied: 'meaning' }), new Set(), 'meaning').columns.get('s0')?.candidates[0];
    expect(c).toMatchObject({ band: 'perfect', similarity: 0.4 });
  });

  it('later pages and dismissals keep the ordering of the first page', () => {
    const first = firstPageResults(response({ order_applied: 'meaning' }), new Set(), 'meaning');
    const more = response({ order_applied: 'rhyme', slots: [{ slot: pslot('s0', [[0, 0, 1], [1, 0, 1]]), candidates: [pcand('dict:z', 'steady', 0.9)], total: 50 }] });
    expect(appendSlotPage(first, 's0', 30, more, new Set()).ordering).toEqual(first.ordering);
    expect(appendLineMatches(first, 30, more, new Set()).ordering).toEqual(first.ordering);
    expect(dismissFromResults(first, 'dict:a').ordering).toEqual(first.ordering);
  });
});
