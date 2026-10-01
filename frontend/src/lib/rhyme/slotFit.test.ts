import { describe, it, expect } from 'vitest';
import { slotFromGridLine, slotFromSnippet, fits, parseManualSlot, lastNonEmptyLineIndex } from './slotFit';
import type { GridLine, Snippet } from '@/types';

const line = (cells: Array<{ s?: number; rest?: boolean }>): GridLine => ({
  cells: cells.map((c) => (c.rest ? { rest: true } : { syllable: 'x', stress: c.s })),
  syllable_count: cells.filter((c) => !c.rest).length,
  raw_text: 'x',
});

describe('slotFromGridLine', () => {
  it('uses one character per cell, rests as 0', () => {
    const slot = slotFromGridLine(line([{ s: 1 }, { s: 0 }, { rest: true }, { s: 2 }]));
    expect(slot).toEqual({ syllable_count: 4, stress: '1002' });
  });

  it('treats missing stress as 0', () => {
    expect(slotFromGridLine(line([{}, { s: 1 }]))).toEqual({ syllable_count: 2, stress: '01' });
  });

  it('returns null for an empty line', () => {
    expect(slotFromGridLine(line([]))).toBeNull();
  });
});

describe('slotFromSnippet', () => {
  const snippet = {
    grid_data: { lines: [line([{ s: 1 }, { s: 0 }]), line([{ s: 0 }, { s: 1 }, { s: 0 }]), line([])] },
  } as unknown as Snippet;

  it('picks the requested line', () => {
    expect(slotFromSnippet(snippet, 0)).toEqual({ syllable_count: 2, stress: '10' });
  });

  it('null line index means the last non-empty line', () => {
    expect(lastNonEmptyLineIndex(snippet)).toBe(1);
    expect(slotFromSnippet(snippet, null)).toEqual({ syllable_count: 3, stress: '010' });
  });

  it('returns null with no grid data or an out-of-range line', () => {
    expect(slotFromSnippet({ grid_data: null } as unknown as Snippet, null)).toBeNull();
    expect(slotFromSnippet(snippet, 9)).toBeNull();
  });
});

describe('fits', () => {
  it('requires equal length', () => {
    expect(fits('10', { syllable_count: 3, stress: '100' })).toBe(false);
    expect(fits('100', { syllable_count: 3, stress: '100' })).toBe(true);
  });

  it('rejects a stressed candidate syllable on a slot 0', () => {
    expect(fits('01', { syllable_count: 2, stress: '10' })).toBe(false);
    expect(fits('21', { syllable_count: 2, stress: '10' })).toBe(false);
  });

  it('allows unstressed candidate syllables anywhere and stress on slot 1/2', () => {
    expect(fits('00', { syllable_count: 2, stress: '10' })).toBe(true);
    expect(fits('10', { syllable_count: 2, stress: '20' })).toBe(true);
    expect(fits('11', { syllable_count: 2, stress: '11' })).toBe(true);
  });
});

describe('parseManualSlot', () => {
  it('accepts a valid count and stress string', () => {
    expect(parseManualSlot('3', '010')).toEqual({ ok: true, slot: { syllable_count: 3, stress: '010' } });
  });

  it('rejects stress strings outside [012]+ and mismatched counts', () => {
    expect(parseManualSlot('2', '13')).toEqual({ ok: false, reason: 'stress' });
    expect(parseManualSlot('2', '')).toEqual({ ok: false, reason: 'stress' });
    expect(parseManualSlot('3', '10')).toEqual({ ok: false, reason: 'count' });
    expect(parseManualSlot('x', '10')).toEqual({ ok: false, reason: 'count' });
    expect(parseManualSlot('0', '')).toEqual({ ok: false, reason: 'stress' });
  });
});

describe('slotFromRange', () => {
  const snippet = {
    grid_data: {
      lines: [
        {
          cells: [
            { syllable: 'or', stress: 1, word_index: 0 },
            { syllable: 'ange', stress: 0, word_index: 0 },
            { rest: true },
            { syllable: 'door', stress: 1, word_index: 1 },
            { syllable: 'hinge', stress: 1, word_index: 2 },
            { rest: true },
          ],
          syllable_count: 4,
          raw_text: 'orange door hinge',
        },
      ],
    },
  } as unknown as Snippet;

  it('derives the slot from the cells of the selected words, interior rests included', async () => {
    const { slotFromRange } = await import('./slotFit');
    expect(slotFromRange(snippet, { lineIndex: 0, wordStart: 1, wordEnd: 3 })).toEqual({ syllable_count: 2, stress: '11' });
    expect(slotFromRange(snippet, { lineIndex: 0, wordStart: 0, wordEnd: 2 })).toEqual({ syllable_count: 4, stress: '1001' });
  });

  it('returns null when the range has no cells or the line is missing', async () => {
    const { slotFromRange } = await import('./slotFit');
    expect(slotFromRange(snippet, { lineIndex: 3, wordStart: 0, wordEnd: 1 })).toBeNull();
    expect(slotFromRange(snippet, { lineIndex: 0, wordStart: 7, wordEnd: 8 })).toBeNull();
  });
});
