import { describe, it, expect } from 'vitest';
import type { GridCell, GridData } from '@/types';
import type { Score } from '@/types/score';
import { commitTrack, followGrid, NO_SCORE } from './track';
import { emptyHistory, record, redo, undo } from './history';

const syl = (syllable: string, word_index: number, word = syllable): GridCell => ({ syllable, stress: 0, word_index, word });
const rest: GridCell = { rest: true };
const grid = (...cells: GridCell[][]): GridData => ({
  lines: cells.map((c) => ({ cells: c, syllable_count: c.filter((x) => !x.rest).length, raw_text: '' })),
});

function score(lines: Score['lines']): Score {
  return { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num: 4, den: 4 }], tempos: [{ bar: 0, bpm: 90 }], snap: '1/16', lines };
}

const g0 = grid([syl('I', 0), syl('go', 1), syl('now', 2)]);
const s0 = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 24, d: 24, a: 0 }, { s: 2, t: 48, d: 24, a: 0 }] }]);

describe('commitTrack', () => {
  it('rest-links the grid and makes it the base', () => {
    const spaced = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 72, d: 24, a: 0 }, { s: 2, t: 96, d: 24, a: 0 }] }]);
    const { track, grid: linked } = commitTrack(spaced, g0);
    expect(linked?.lines[0].cells).toHaveLength(4);
    expect(linked?.lines[0].raw_text).toBe('I _ go now');
    expect(track.base?.grid).toBe(linked);
  });

  it('is the empty track without a score', () => {
    expect(commitTrack(null, g0)).toEqual({ track: NO_SCORE, grid: g0 });
  });
});

describe('followGrid', () => {
  const { track } = commitTrack(s0, g0);

  it('moves notes for a rest-only edit and rebases', () => {
    const next = grid([syl('I', 0), syl('go', 1), rest, rest, syl('now', 2)]);
    const result = followGrid(track, g0, next);
    if (result.kind !== 'followed') throw new Error(result.kind);
    expect(result.track.score?.lines[0].notes[2].t).toBe(144);
    expect(result.track.base?.score).toBe(result.track.score);
    expect(result.grid?.lines[0].cells).toHaveLength(5);
  });

  it('reconciles typing from the base, so a retyped word gets its notes back', () => {
    const partial = grid([syl('I', 0), syl('go', 1), syl('no', 2)]);
    const step1 = followGrid(track, g0, partial);
    if (step1.kind !== 'followed') throw new Error(step1.kind);
    expect(step1.track.score?.lines[0].notes.map((n) => n.s)).toEqual([0, 1]);
    const step2 = followGrid(step1.track, partial, g0);
    if (step2.kind !== 'followed') throw new Error(step2.kind);
    expect(step2.track.score?.lines[0].notes.map((n) => n.s)).toEqual([0, 1, 2]);
  });

  it('is unchanged without a score or without a grid change', () => {
    expect(followGrid(NO_SCORE, g0, grid([syl('x', 0)]))).toEqual({ kind: 'unchanged' });
    expect(followGrid(track, g0, grid([syl('I', 0), syl('go', 1), syl('now', 2)]))).toEqual({ kind: 'unchanged' });
  });

  it('refuses a rest edit that would pass MAX_TICKS', () => {
    const late = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 393000, d: 24, a: 0 }, { s: 2, t: 393100, d: 24, a: 0 }] }]);
    const t = { score: late, base: { score: late, grid: g0 } };
    const result = followGrid(t, g0, grid([syl('I', 0), rest, rest, rest, rest, rest, rest, syl('go', 1), syl('now', 2)]));
    expect(result.kind).toBe('refused');
  });
});

describe('history', () => {
  it('records, undoes and redoes snapshots', () => {
    let h = emptyHistory<number>();
    h = record(h, 1);
    h = record(h, 2);
    const u = undo(h, 3)!;
    expect(u.value).toBe(2);
    const r = redo(u.history, 2)!;
    expect(r.value).toBe(3);
    expect(undo(emptyHistory<number>(), 0)).toBeNull();
    expect(redo(h, 0)).toBeNull();
  });

  it('keeps at most the limit and clears redo on record', () => {
    let h = emptyHistory<number>();
    for (let i = 0; i < 10; i++) h = record(h, i, 3);
    expect(h.past).toEqual([7, 8, 9]);
    const u = undo(h, 10)!;
    expect(record(u.history, 11).future).toEqual([]);
  });
});
