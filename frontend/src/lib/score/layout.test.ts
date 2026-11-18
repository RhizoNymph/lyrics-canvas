import { describe, it, expect } from 'vitest';
import type { Score } from '@/types/score';
import { beatTicks, layoutStaff, lineStarts, noteSegments, rowAtTick, staffBars, tickAtX, xOfTick } from './layout';

function score(lines: Score['lines'], extra: Partial<Score> = {}): Score {
  return { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num: 4, den: 4 }], tempos: [{ bar: 0, bpm: 90 }], snap: '1/16', lines, ...extra };
}

describe('staffBars', () => {
  it('lists the pickup and bars per meter segment, marking changes', () => {
    const s = score([], {
      pickup: 24,
      meters: [
        { bar: 0, num: 4, den: 4 },
        { bar: 2, num: 6, den: 8 },
      ],
      tempos: [
        { bar: 0, bpm: 90 },
        { bar: 3, bpm: 120 },
      ],
    });
    const bars = staffBars(s, 4);
    expect(bars.map((b) => [b.bar, b.start, b.length, b.meterChange])).toEqual([
      [-1, 0, 24, false],
      [0, 24, 192, true],
      [1, 216, 192, false],
      [2, 408, 144, true],
      [3, 552, 144, false],
    ]);
    expect(bars[4].tempoChange).toEqual({ bar: 3, bpm: 120 });
    expect(bars[2].tempoChange).toBeNull();
  });
});

describe('layoutStaff', () => {
  it('wraps bars into rows no wider than the width, plus one empty bar', () => {
    const s = score([{ notes: [{ s: 0, t: 0, d: 400, a: 0 }] }]);
    // 400 ticks -> 3 bars + 1 extra = 4 bars of 192 ticks at 1 px/tick, 400 px rows: 2 bars per row.
    const layout = layoutStaff(s, { width: 400, pxPerTick: 1 });
    expect(layout.rows.map((r) => [r.start, r.end, r.bars.length])).toEqual([
      [0, 384, 2],
      [384, 768, 2],
    ]);
    expect(layout.width).toBe(384);
    expect(layout.end).toBe(768);
  });

  it('gives an over-wide bar a row of its own and respects minBars', () => {
    const layout = layoutStaff(score([]), { width: 100, pxPerTick: 1, minBars: 3 });
    expect(layout.rows.map((r) => r.bars.length)).toEqual([1, 1, 1]);
  });

  it('maps ticks to rows and x', () => {
    const layout = layoutStaff(score([]), { width: 400, pxPerTick: 0.5, minBars: 8 });
    expect(rowAtTick(layout, 0)).toBe(0);
    expect(rowAtTick(layout, 767)).toBe(0);
    expect(rowAtTick(layout, 768)).toBe(1);
    expect(rowAtTick(layout, 100000)).toBe(layout.rows.length - 1);
    const row = layout.rows[1];
    expect(xOfTick(layout, row, 800)).toBe(16);
    expect(tickAtX(layout, row, 16)).toBe(800);
  });

  it('lays out a long score in linear time', () => {
    const notes = Array.from({ length: 4000 }, (_, i) => ({ s: i, t: i * 24, d: 24, a: 0 as const }));
    const s = score([{ notes }]);
    const started = performance.now();
    const layout = layoutStaff(s, { width: 600, pxPerTick: 0.8 });
    const segments = noteSegments(s, layout);
    expect(performance.now() - started).toBeLessThan(200);
    expect(segments.flat().filter((g) => g.head)).toHaveLength(4000);
  });
});

describe('beatTicks', () => {
  it('marks downbeat, beats and half beats in 4/4', () => {
    const [bar] = staffBars(score([]), 1);
    expect(beatTicks(bar).map((b) => [b.tick, b.weight])).toEqual([
      [0, 3],
      [24, 1],
      [48, 2],
      [72, 1],
      [96, 2],
      [120, 1],
      [144, 2],
      [168, 1],
    ]);
  });

  it('uses dotted beats in 6/8 and the tail of a virtual bar in the pickup', () => {
    const six = staffBars(score([], { meters: [{ bar: 0, num: 6, den: 8 }] }), 1)[0];
    expect(beatTicks(six).filter((b) => b.weight >= 2).map((b) => b.tick)).toEqual([0, 72]);
    const pickup = staffBars(score([], { pickup: 48 }), 1)[0];
    expect(beatTicks(pickup).map((b) => [b.tick, b.weight])).toEqual([
      [0, 2],
      [24, 1],
    ]);
  });
});

describe('noteSegments and lineStarts', () => {
  it('splits a note across rows and marks the head row', () => {
    const s = score([{ notes: [{ s: 0, t: 300, d: 200, a: 2 }] }, { notes: [] }, { notes: [{ s: 0, t: 600, d: 10, a: 0 }] }]);
    const layout = layoutStaff(s, { width: 400, pxPerTick: 1 });
    const segs = noteSegments(s, layout);
    expect(segs[0]).toEqual([{ line: 0, s: 0, t: 300, d: 200, a: 2, from: 300, to: 384, head: true }]);
    expect(segs[1][0]).toMatchObject({ from: 384, to: 500, head: false });
    expect(lineStarts(s)).toEqual([
      { line: 0, tick: 300 },
      { line: 2, tick: 600 },
    ]);
  });
});
