import { describe, it, expect } from 'vitest';
import type { GridCell, GridData, GridLine } from '@/types';
import type { Score } from '@/types/score';
import { planContentSave, ScoreSaveError } from './saveFlow';
import type { ScoreContent } from './saveFlow';

const syl = (syllable: string, word_index: number, word = syllable): GridCell => ({ syllable, stress: 0, word_index, word });
const rest: GridCell = { rest: true };

function line(cells: GridCell[], raw_text: string): GridLine {
  return { cells, syllable_count: cells.filter((c) => !c.rest).length, raw_text };
}

const iGoNow = (): GridData => ({ lines: [line([syl('I', 0), syl('go', 1), syl('now', 2)], 'I go now')] });

function score(lines: Score['lines']): Score {
  return { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num: 4, den: 4 }], tempos: [{ bar: 0, bpm: 90 }], snap: '1/16', lines };
}

const placed = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 24, d: 24, a: 0 }, { s: 2, t: 48, d: 24, a: 0 }] }]);

function content(overrides: Partial<ScoreContent> = {}): ScoreContent {
  return { raw_text: 'I go now', grid_data: iGoNow(), score: placed, score_view: true, ...overrides };
}

describe('planContentSave', () => {
  it('leaves a body alone when the snippet has no score and none is sent', () => {
    const body = { raw_text: 'edited' };
    const plan = planContentSave(content({ score: null, score_view: false }), body);
    expect(plan.body).toBe(body);
    expect(plan.local).toEqual({});
  });

  it('leaves a layout-only body alone even with a score', () => {
    const body = { position_x: 3 };
    const plan = planContentSave(content(), body);
    expect(plan.body).toBe(body);
  });

  it('removes the score and forces score_view off on an explicit null', () => {
    const plan = planContentSave(content(), { score: null });
    expect(plan.body).toEqual({ score: null, score_view: false });
    expect(plan.local).toEqual({ score: null, score_view: false });
  });

  it('applies grid -> score for a rest-only grid edit and keeps the text in step', () => {
    const grid: GridData = { lines: [line([syl('I', 0), syl('go', 1), rest, rest, syl('now', 2)], 'I go _ _ now')] };
    const plan = planContentSave(content(), { grid_data: grid, raw_text: 'I go _ _ now' });
    expect(plan.local.score?.lines[0].notes.map((n) => n.t)).toEqual([0, 24, 144]);
    expect(plan.local.grid_data).toEqual(grid);
    expect(plan.local.raw_text).toBe('I go _ _ now');
    expect(plan.body.score).toEqual(plan.local.score);
    expect(plan.body.grid_data).toEqual(grid);
  });

  it('reconciles a text change: matched words keep their notes, new ones are unplaced', () => {
    const grid: GridData = { lines: [line([syl('I', 0), syl('go', 1), syl('right', 2), syl('now', 3)], 'I go right now')] };
    const plan = planContentSave(content(), { grid_data: grid, raw_text: 'I go right now' });
    expect(plan.local.score?.lines[0].notes.map((n) => [n.s, n.t])).toEqual([
      [0, 0],
      [1, 24],
      [3, 48],
    ]);
  });

  it('writes a gap between placed syllables into the grid and the snippet text', () => {
    const moved = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 72, d: 24, a: 0 }, { s: 2, t: 96, d: 24, a: 0 }] }]);
    const plan = planContentSave(content({ raw_text: '\nI go now\n' }), { score: moved });
    expect(plan.local.raw_text).toBe('\nI _ go now\n');
    expect(plan.local.grid_data?.lines[0].cells.map((c) => (c.rest ? '_' : c.syllable))).toEqual(['I', '_', 'go', 'now']);
    expect(plan.body).toMatchObject({ score: moved, raw_text: '\nI _ go now\n' });
    expect(plan.body.grid_data).toEqual(plan.local.grid_data);
  });

  it('keeps score_view false when a score is created without a view change', () => {
    const plan = planContentSave(content({ score: null, score_view: false }), { score: placed });
    expect(plan.local.score_view).toBeUndefined();
    expect(plan.body.score).toEqual(placed);
  });

  it('sends score_view and forces it off without a score', () => {
    expect(planContentSave(content(), { score_view: false }).body).toEqual({ score_view: false });
    expect(planContentSave(content({ score: null, score_view: false }), { score_view: true }).local.score_view).toBe(false);
  });

  it('refuses a sent score that does not fit the grid', () => {
    const bad = score([{ notes: [{ s: 5, t: 0, d: 12, a: 0 }] }]);
    expect(() => planContentSave(content(), { score: bad })).toThrow(ScoreSaveError);
  });

  it('refuses a rest edit that pushes a note past MAX_TICKS', () => {
    const late = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 393000, d: 24, a: 0 }, { s: 2, t: 393100, d: 24, a: 0 }] }]);
    const old: GridData = { lines: [line([syl('I', 0), ...Array<GridCell>(8186).fill(rest), syl('go', 1), syl('now', 2)], 'x')] };
    // The old grid matches the score's gap, so this is a pure rest addition at boundary 0.
    const longer: GridData = { lines: [line([syl('I', 0), ...Array<GridCell>(8200).fill(rest), syl('go', 1), syl('now', 2)], 'x')] };
    expect(() => planContentSave(content({ grid_data: old, score: late }), { grid_data: longer })).toThrow(ScoreSaveError);
  });

  it('keeps raw_text as sent when its non-blank lines do not match the grid', () => {
    const moved = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 72, d: 24, a: 0 }, { s: 2, t: 96, d: 24, a: 0 }] }]);
    const plan = planContentSave(content({ raw_text: '' }), { score: moved });
    expect(plan.local.raw_text).toBe('');
  });
});
