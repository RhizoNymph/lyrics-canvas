import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { RhymeOrderToggleView } from './RhymeOrderToggle';
import RhymeOrderToggle from './RhymeOrderToggle';
import { BandHeader, OrderNote, SimilarityBadge } from './orderParts';
import { CandidateListView } from '../CandidateList';
import type { CandidateListProps } from '../CandidateList';
import SlotColumns from '../pattern/SlotColumns';
import RhymesWithView from '@/components/wordBank/RhymesWithView';
import { EMPTY_PICKS, slotViews } from '@/lib/rhyme/pattern';
import type { SlotColumn, SlotEditState } from '@/lib/rhyme/pattern';
import { pcand, pslot } from '@/lib/rhyme/pattern/fixtures';
import { bw } from '@/lib/wordBank/fixtures';
import type { PatternCandidate } from '@/types/rhymePattern';
import type { RhymeBand } from '@/lib/rhyme/order';

vi.mock('@/api/client', () => ({
  nlpApi: { rhymeSuggest: vi.fn() },
  rhymePatternApi: { detect: vi.fn(), suggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  wordBankApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const noop = () => undefined;

/** Header labels in document order. */
const headers = (html: string) => [...html.matchAll(/data-band="(\w+)"/g)].map((m) => m[1]);

function cand(id: string, score: number, band: RhymeBand, similarity: number | null): PatternCandidate {
  return { ...pcand(id, id, score), band, similarity };
}

describe('RhymeOrderToggleView', () => {
  it('shows both orders as a radio group with the current one checked', () => {
    const html = renderToStaticMarkup(<RhymeOrderToggleView order="meaning" onChange={noop} label="Order" />);
    expect(html).toContain('role="radiogroup"');
    expect(html).toContain('aria-label="Rhyme order"');
    expect(html).toMatch(/aria-checked="false"[^>]*>Rhyme<\/button>/);
    expect(html).toMatch(/aria-checked="true"[^>]*>Rhyme, then meaning<\/button>/);
    expect(html).toContain('Order');
  });

  it('the connected toggle renders both choices', () => {
    const html = renderToStaticMarkup(<RhymeOrderToggle />);
    expect(html).toContain('Rhyme, then meaning');
    expect(html.match(/role="radio"/g)).toHaveLength(2);
  });
});

describe('order parts', () => {
  it('BandHeader labels the band', () => {
    const html = renderToStaticMarkup(<BandHeader band="near" />);
    expect(html).toContain('data-band="near"');
    expect(html).toContain('Near');
    expect(html).toContain('role="heading"');
  });

  it('SimilarityBadge shows a percent, or nothing', () => {
    expect(renderToStaticMarkup(<SimilarityBadge similarity={0.734} />)).toContain('≈73%');
    expect(renderToStaticMarkup(<SimilarityBadge similarity={null} />)).toBe('');
    expect(renderToStaticMarkup(<SimilarityBadge similarity={undefined} />)).toBe('');
  });

  it('OrderNote only speaks when meaning was unavailable', () => {
    expect(renderToStaticMarkup(<OrderNote presentation="unavailable" />)).toContain('Meaning order unavailable');
    expect(renderToStaticMarkup(<OrderNote presentation="bands" />)).toBe('');
    expect(renderToStaticMarkup(<OrderNote presentation="plain" />)).toBe('');
  });
});

describe('CandidateList (rhyme sidebar)', () => {
  const rows = [cand('dict:a', 0.97, 'perfect', 0.2), cand('dict:b', 0.96, 'perfect', 0.1), cand('dict:c', 0.9, 'strong', 0.9), cand('dict:d', 0.5, 'loose', 0.8)];

  const asyncNoop = async () => undefined;
  const props = (ordering: CandidateListProps['ordering']): CandidateListProps => ({
    candidates: rows, anchors: [], dismissed: new Set(), offset: 0, total: rows.length, slot: null, loading: false, error: null, ordering,
    loadMore: asyncNoop, dismiss: noop, materialize: asyncNoop, setTypedAnchor: noop,
  });

  it('shows band headers where the band changes when meaning was applied', () => {
    const html = renderToStaticMarkup(<CandidateListView {...props({ requested: 'meaning', applied: 'meaning' })} />);
    expect(headers(html)).toEqual(['perfect', 'strong', 'loose']);
    expect(html.indexOf('Perfect')).toBeLessThan(html.indexOf('dict:a'));
    expect(html.indexOf('Strong')).toBeGreaterThan(html.indexOf('dict:b'));
    expect(html).toContain('≈90%');
    expect(html).not.toContain('Meaning order unavailable');
  });

  it('shows no headers in rhyme order', () => {
    const html = renderToStaticMarkup(<CandidateListView {...props({ requested: 'rhyme', applied: 'rhyme' })} />);
    expect(headers(html)).toEqual([]);
    expect(html).not.toContain('Meaning order unavailable');
  });

  it('shows the quiet note and no headers when the server fell back', () => {
    const html = renderToStaticMarkup(<CandidateListView {...props({ requested: 'meaning', applied: 'rhyme' })} />);
    expect(headers(html)).toEqual([]);
    expect(html).toContain('Meaning order unavailable');
  });
});

describe('SlotColumns (pattern)', () => {
  const edit: SlotEditState = {
    lines: [{ line: 0, wordCount: 3 }, { line: 1, wordCount: 3 }],
    slots: [{ id: 's0', on: true, members: [{ line: 0, start: 0, end: 1 }, { line: 1, start: 0, end: 1 }] }],
    nextUserId: 0,
  };
  const slot = pslot('s0', [[0, 0, 1], [1, 0, 1]]);
  const views = slotViews(edit, new Map([['s0', slot]]));
  const column: SlotColumn = { slot, candidates: [cand('dict:a', 0.97, 'perfect', 0.5), cand('dict:b', 0.8, 'near', 0.6)], total: 2, offset: 0 };
  const render = (presentation?: 'plain' | 'bands' | 'unavailable') =>
    renderToStaticMarkup(
      <SlotColumns slots={views} columns={new Map([['s0', column]])} picks={EMPTY_PICKS} paging={new Set()} loading={false} onPick={noop} onDismiss={noop} onMore={noop} presentation={presentation} />,
    );

  it('bands each column when meaning was applied', () => {
    const html = render('bands');
    expect(headers(html)).toEqual(['perfect', 'near']);
    expect(html).toContain('≈60%');
  });

  it('is plain by default and when the fallback happened', () => {
    expect(headers(render())).toEqual([]);
    expect(headers(render('unavailable'))).toEqual([]);
  });
});

describe('RhymesWithView (word bank)', () => {
  const base = { text: '', minScore: 0.5, selected: new Set<string>(), onText: noop, onMinScore: noop, onToggleWord: noop, onSelectAll: noop, selectedWordText: 'moon', selectedCount: 1 };
  const anchor = { text: 'moon', rhyme_key: ['UW1', 'N'], syllable_count: 1 };
  const matches = [
    { word: bw('a', 'tune'), score: 0.97, match_depth: 2, band: 'perfect' as const, similarity: 0.3 },
    { word: bw('b', 'June'), score: 0.9, match_depth: 2, band: 'strong' as const, similarity: 0.6 },
  ];

  it('has the order toggle', () => {
    expect(renderToStaticMarkup(<RhymesWithView {...base} state={{ kind: 'idle' }} />)).toContain('aria-label="Rhyme order"');
  });

  it('bands the matches when meaning was applied', () => {
    const html = renderToStaticMarkup(<RhymesWithView {...base} requestedOrder="meaning" state={{ kind: 'ready', value: { anchor, matches, order_applied: 'meaning' } }} />);
    expect(headers(html)).toEqual(['perfect', 'strong']);
    expect(html).toContain('≈60%');
  });

  it('notes the fallback and shows no headers', () => {
    const html = renderToStaticMarkup(<RhymesWithView {...base} requestedOrder="meaning" state={{ kind: 'ready', value: { anchor, matches, order_applied: 'rhyme' } }} />);
    expect(headers(html)).toEqual([]);
    expect(html).toContain('Meaning order unavailable');
  });

  it('is plain in rhyme order', () => {
    const html = renderToStaticMarkup(<RhymesWithView {...base} state={{ kind: 'ready', value: { anchor, matches, order_applied: 'rhyme' } }} />);
    expect(headers(html)).toEqual([]);
    expect(html).not.toContain('Meaning order unavailable');
  });
});
