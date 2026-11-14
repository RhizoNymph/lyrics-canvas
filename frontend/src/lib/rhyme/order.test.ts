import { describe, it, expect, vi } from 'vitest';
import {
  BAND_LABELS, BAND_MIN_SCORE, DEFAULT_RHYME_ORDER, MEANING_UNAVAILABLE_NOTE, RHYME_BANDS, RHYME_ORDERING, RHYME_ORDER_LABELS,
  RHYME_ORDER_STORAGE_KEY, bandOf, loadRhymeOrder, orderField, orderPresentation, parseRhymeOrder, presentationOf,
  resultOrdering, rowBand, saveRhymeOrder, similarityPercent, withBandHeaders,
} from './order';
import type { BandedItem, RhymeBand } from './order';
import type { KeyValueStorage } from '@/lib/listMode/viewMode';

function memoryStorage(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
  };
}

const throwing: KeyValueStorage = {
  getItem: () => {
    throw new Error('SecurityError');
  },
  setItem: () => {
    throw new Error('QuotaExceededError');
  },
};

type Row = { id: string; score: number; band?: RhymeBand };
const r = (id: string, score: number, band?: RhymeBand): Row => (band === undefined ? { id, score } : { id, score, band });

/** Compact view of a banded list: "#band" for headers, the id for rows. */
const shape = (items: BandedItem<Row>[]) => items.map((i) => (i.kind === 'header' ? `#${i.band}` : i.row.id));

describe('constants', () => {
  it('has the four bands in rhyme order with the contract edges', () => {
    expect(RHYME_BANDS).toEqual(['perfect', 'strong', 'near', 'loose']);
    expect(BAND_MIN_SCORE).toEqual({ perfect: 0.95, strong: 0.85, near: 0.75 });
    expect(BAND_LABELS).toEqual({ perfect: 'Perfect', strong: 'Strong', near: 'Near', loose: 'Loose' });
  });

  it('labels the two orders as the toggle shows them', () => {
    expect(RHYME_ORDER_LABELS).toEqual({ rhyme: 'Rhyme', meaning: 'Rhyme, then meaning' });
    expect(DEFAULT_RHYME_ORDER).toBe('rhyme');
    expect(MEANING_UNAVAILABLE_NOTE).toBe('Meaning order unavailable');
  });
});

describe('bandOf', () => {
  it('puts each edge in the higher band (inclusive lower edge)', () => {
    expect(bandOf(1)).toBe('perfect');
    expect(bandOf(0.95)).toBe('perfect');
    expect(bandOf(0.9499)).toBe('strong');
    expect(bandOf(0.85)).toBe('strong');
    expect(bandOf(0.8499)).toBe('near');
    expect(bandOf(0.75)).toBe('near');
    expect(bandOf(0.7499)).toBe('loose');
    expect(bandOf(0)).toBe('loose');
  });
});

describe('rowBand', () => {
  it('prefers the band the server sent', () => {
    expect(rowBand(r('a', 0.5, 'perfect'))).toBe('perfect');
  });

  it('derives the band from the score when the row has none', () => {
    expect(rowBand(r('a', 0.9))).toBe('strong');
  });
});

describe('orderField', () => {
  it('leaves rhyme (the server default) out and sends meaning', () => {
    expect(orderField('rhyme')).toEqual({});
    expect(orderField('meaning')).toEqual({ order: 'meaning' });
  });
});

describe('orderPresentation', () => {
  it('is plain for rhyme asked and given (or absent)', () => {
    expect(orderPresentation('rhyme', 'rhyme')).toBe('plain');
    expect(orderPresentation('rhyme', undefined)).toBe('plain');
  });

  it('is bands whenever meaning was applied', () => {
    expect(orderPresentation('meaning', 'meaning')).toBe('bands');
  });

  it('is unavailable when meaning was asked but rhyme came back (or nothing was said)', () => {
    expect(orderPresentation('meaning', 'rhyme')).toBe('unavailable');
    expect(orderPresentation('meaning', undefined)).toBe('unavailable');
  });

  it('reads a ResultOrdering the same way', () => {
    expect(presentationOf(RHYME_ORDERING)).toBe('plain');
    expect(presentationOf(resultOrdering('meaning', 'meaning'))).toBe('bands');
    expect(presentationOf(resultOrdering('meaning', undefined))).toBe('unavailable');
  });
});

describe('resultOrdering', () => {
  it('defaults the applied order to rhyme when the server did not say', () => {
    expect(resultOrdering('meaning', undefined)).toEqual({ requested: 'meaning', applied: 'rhyme' });
    expect(resultOrdering('rhyme', 'rhyme')).toEqual({ requested: 'rhyme', applied: 'rhyme' });
  });
});

describe('withBandHeaders', () => {
  const rows = [r('a', 0.99, 'perfect'), r('b', 0.96, 'perfect'), r('c', 0.9, 'strong'), r('d', 0.8, 'near'), r('e', 0.4, 'loose')];

  it('returns just the rows unless the list is banded', () => {
    expect(shape(withBandHeaders(rows, 'plain'))).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(shape(withBandHeaders(rows, 'unavailable'))).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('puts a header before the first row of each band', () => {
    expect(shape(withBandHeaders(rows, 'bands'))).toEqual(['#perfect', 'a', 'b', '#strong', 'c', '#near', 'd', '#loose', 'e']);
  });

  it('skips bands with no rows', () => {
    expect(shape(withBandHeaders([r('a', 0.99, 'perfect'), r('e', 0.4, 'loose')], 'bands'))).toEqual(['#perfect', 'a', '#loose', 'e']);
  });

  it('continues the current band across an appended page (no repeated header)', () => {
    const page1 = [r('a', 0.9, 'strong'), r('b', 0.88, 'strong')];
    const page2 = [r('c', 0.86, 'strong'), r('d', 0.8, 'near')];
    expect(shape(withBandHeaders([...page1, ...page2], 'bands'))).toEqual(['#strong', 'a', 'b', 'c', '#near', 'd']);
  });

  it('uses the band the server sent, not the score', () => {
    expect(shape(withBandHeaders([r('a', 0.5, 'perfect'), r('b', 0.99, 'loose')], 'bands'))).toEqual(['#perfect', 'a', '#loose', 'b']);
  });

  it('falls back to the score band for rows without one', () => {
    expect(shape(withBandHeaders([r('a', 0.97), r('b', 0.8)], 'bands'))).toEqual(['#perfect', 'a', '#near', 'b']);
  });

  it('gives every header a unique key even if a band comes back', () => {
    const items = withBandHeaders([r('a', 0.9, 'strong'), r('b', 0.8, 'near'), r('c', 0.9, 'strong')], 'bands');
    const keys = items.filter((i) => i.kind === 'header').map((i) => (i.kind === 'header' ? i.key : ''));
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys).toHaveLength(3);
  });

  it('is empty for no rows', () => {
    expect(withBandHeaders([], 'bands')).toEqual([]);
  });
});

describe('similarityPercent', () => {
  it('rounds to a whole percent, clamped to 0..100', () => {
    expect(similarityPercent(0.7234)).toBe(72);
    expect(similarityPercent(0.725)).toBe(73);
    expect(similarityPercent(1.2)).toBe(100);
    expect(similarityPercent(-0.3)).toBe(0);
  });

  it('is null for a missing or non-finite similarity', () => {
    expect(similarityPercent(null)).toBeNull();
    expect(similarityPercent(undefined)).toBeNull();
    expect(similarityPercent(Number.NaN)).toBeNull();
  });
});

describe('persistence', () => {
  it('parses only the two orders', () => {
    expect(parseRhymeOrder('rhyme')).toBe('rhyme');
    expect(parseRhymeOrder('meaning')).toBe('meaning');
    expect(parseRhymeOrder('Meaning')).toBeNull();
    expect(parseRhymeOrder(null)).toBeNull();
  });

  it('loads a stored order', () => {
    expect(loadRhymeOrder(memoryStorage({ [RHYME_ORDER_STORAGE_KEY]: 'meaning' }))).toBe('meaning');
  });

  it('defaults to rhyme for nothing, garbage, no storage or a throwing storage', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(loadRhymeOrder(memoryStorage())).toBe('rhyme');
    expect(loadRhymeOrder(memoryStorage({ [RHYME_ORDER_STORAGE_KEY]: 'zzz' }))).toBe('rhyme');
    expect(loadRhymeOrder(null)).toBe('rhyme');
    expect(loadRhymeOrder(throwing)).toBe('rhyme');
  });

  it('saves the order under its own key', () => {
    const s = memoryStorage();
    expect(saveRhymeOrder(s, 'meaning')).toEqual({ ok: true });
    expect(s.data[RHYME_ORDER_STORAGE_KEY]).toBe('meaning');
    expect(RHYME_ORDER_STORAGE_KEY).toBe('lyrics-canvas.rhymeOrder');
  });

  it('reports a typed failure instead of throwing', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(saveRhymeOrder(null, 'meaning')).toEqual({ ok: false, error: { kind: 'unavailable' } });
    expect(saveRhymeOrder(throwing, 'meaning')).toMatchObject({ ok: false, error: { kind: 'write_failed' } });
  });
});
