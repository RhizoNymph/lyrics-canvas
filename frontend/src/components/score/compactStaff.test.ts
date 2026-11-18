import { describe, it, expect } from 'vitest';
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import { compactLayout, compactStaffShapes, showsStaff } from './compactStaff';

const grid: GridData = {
  lines: [
    {
      cells: [
        { syllable: 'I', stress: 1, word_index: 0 },
        { syllable: 'go', stress: 0, word_index: 1 },
      ],
      syllable_count: 2,
      raw_text: 'I go',
    },
  ],
};

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/16',
  lines: [{ notes: [{ s: 0, t: 0, d: 24, a: 1 }, { s: 1, t: 200, d: 24, a: 0 }] }],
};

describe('compactStaffShapes', () => {
  it('draws a head, bar and syllable per note and an accent mark for accented notes', () => {
    const shapes = compactStaffShapes(score, grid, 600, { s: 1, left: 10, top: 20 });
    expect(shapes.heads).toHaveLength(2);
    expect(shapes.noteBars).toHaveLength(2);
    expect(shapes.texts.filter((t) => !t.accent).map((t) => t.text)).toEqual(['I', 'go']);
    expect(shapes.texts.filter((t) => t.accent).map((t) => t.text)).toEqual(['>']);
    expect(shapes.noteBars[0]).toMatchObject({ x: 10, w: 12 });
  });

  it('wraps bars to the width and scales with s', () => {
    // Two bars of 96 px at 0.5 px/tick in a 100 px wide card: one bar per row.
    expect(compactLayout(score, 100).rows).toHaveLength(2);
    const shapes = compactStaffShapes(score, grid, 100, { s: 2, left: 0, top: 0 });
    expect(shapes.height).toBe(2 * 32 * 2);
  });
});

describe('showsStaff', () => {
  it('needs both a score and the staff view', () => {
    expect(showsStaff({ score, score_view: true })).toBe(true);
    expect(showsStaff({ score, score_view: false })).toBe(false);
    expect(showsStaff({ score: null, score_view: true })).toBe(false);
    expect(showsStaff({})).toBe(false);
  });
});
