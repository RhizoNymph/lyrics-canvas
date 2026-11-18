import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { RhythmFitToggleView } from './RhythmFitToggle';
import { RhythmBadge, RhythmNote } from './rhythmParts';
import { CandidateListView } from '../CandidateList';
import type { CandidateListProps } from '../CandidateList';
import CandidateRow from '../CandidateRow';
import { RHYME_ORDERING } from '@/lib/rhyme/order';
import type { RhymeBand } from '@/lib/rhyme/order';
import { RHYTHM_OFF } from '@/lib/rhyme/rhythmFit';
import type { RhymeCandidate } from '@/types/rhyme';

vi.mock('@/api/client', () => ({
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  wordBankApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const noop = () => undefined;
const headers = (html: string) => [...html.matchAll(/data-band="(\w+)"/g)].map((m) => m[1]);

function cand(id: string, score: number, band: RhymeBand, rhythm_fit: number | null): RhymeCandidate {
  return {
    id,
    text: id,
    provenance: 'dictionary',
    ephemeral: true,
    syllable_count: 1,
    stress: '1',
    rhyme_key: ['IH', 'N'],
    syllables: [],
    grid_line: { cells: [], syllable_count: 1, raw_text: id },
    tags: [],
    score,
    match_depth: 1,
    best_anchor_id: 'x',
    fits_slot: true,
    alignment: [],
    source: 'dictionary',
    band,
    similarity: null,
    rhythm_fit,
  };
}

function listProps(patch: Partial<CandidateListProps>): CandidateListProps {
  return {
    candidates: [],
    anchors: [],
    dismissed: new Set(),
    offset: 0,
    total: 0,
    slot: null,
    loading: false,
    error: null,
    ordering: RHYME_ORDERING,
    loadMore: async () => undefined,
    dismiss: noop,
    materialize: async () => undefined,
    setTypedAnchor: noop,
    ...patch,
  };
}

describe('RhythmFitToggleView', () => {
  it('is a checkbox labelled Fit rhythm reflecting the switch', () => {
    const on = renderToStaticMarkup(<RhythmFitToggleView on onChange={noop} />);
    expect(on).toContain('Fit rhythm');
    expect(on).toContain('type="checkbox"');
    expect(on).toContain('checked=""');
    const off = renderToStaticMarkup(<RhythmFitToggleView on={false} onChange={noop} />);
    expect(off).not.toContain('checked=""');
  });
});

describe('rhythm parts', () => {
  it('RhythmBadge shows a percent only with a value', () => {
    const html = renderToStaticMarkup(<RhythmBadge fit={0.75} />);
    expect(html).toContain('data-rhythm-fit');
    expect(html).toContain('75%');
    expect(renderToStaticMarkup(<RhythmBadge fit={null} />)).toBe('');
    expect(renderToStaticMarkup(<RhythmBadge fit={undefined} />)).toBe('');
  });

  it('RhythmNote names the reason only when unavailable', () => {
    expect(renderToStaticMarkup(<RhythmNote rhythm={RHYTHM_OFF} />)).toBe('');
    expect(renderToStaticMarkup(<RhythmNote rhythm={{ requested: true, applied: true, unavailable: null }} />)).toBe('');
    const html = renderToStaticMarkup(<RhythmNote rhythm={{ requested: true, applied: false, unavailable: 'no_score' }} />);
    expect(html).toContain('role="note"');
    expect(html).toMatch(/no score/);
  });
});

describe('CandidateRow with rhythm', () => {
  it('shows the rhythm percent next to the score', () => {
    const html = renderToStaticMarkup(
      <CandidateRow
        candidate={cand('dict:a', 0.97, 'perfect', 0.5)}
        anchor={undefined}
        hasSlot={false}
        expanded={false}
        onToggle={noop}
        onChain={noop}
        onMaterialize={noop}
        onDismiss={noop}
      />,
    );
    expect(html).toContain('data-rhythm-fit');
    expect(html).toContain('50%');
  });
});

describe('CandidateListView with rhythm', () => {
  const rows = [cand('dict:a', 0.97, 'perfect', 1), cand('dict:b', 0.96, 'perfect', 0.5), cand('dict:c', 0.9, 'strong', 1)];

  it('bands the rows when rhythm fit was applied in rhyme order', () => {
    const html = renderToStaticMarkup(
      <CandidateListView {...listProps({ candidates: rows, total: 3, rhythm: { requested: true, applied: true, unavailable: null } })} />,
    );
    expect(headers(html)).toEqual(['perfect', 'strong']);
    expect(html.match(/data-rhythm-fit/g)).toHaveLength(3);
  });

  it('draws no headers without rhythm (rhyme order, as before)', () => {
    const html = renderToStaticMarkup(<CandidateListView {...listProps({ candidates: rows, total: 3 })} />);
    expect(headers(html)).toEqual([]);
  });

  it('shows the unavailable reason', () => {
    const html = renderToStaticMarkup(
      <CandidateListView
        {...listProps({ candidates: rows, total: 3, rhythm: { requested: true, applied: false, unavailable: 'slot_unplaced' } })}
      />,
    );
    expect(html).toMatch(/not placed on the staff/);
    expect(headers(html)).toEqual([]);
  });

  it('keeps the meaning note when meaning fell back but rhythm banded', () => {
    const html = renderToStaticMarkup(
      <CandidateListView
        {...listProps({
          candidates: rows,
          total: 3,
          ordering: { requested: 'meaning', applied: 'rhyme' },
          rhythm: { requested: true, applied: true, unavailable: null },
        })}
      />,
    );
    expect(html).toContain('Meaning order unavailable');
    expect(headers(html)).toEqual(['perfect', 'strong']);
  });
});
