import { describe, it, expect } from 'vitest';
import type { Score } from '@/types/score';
import { ACCENT_GAIN, msToTick, playbackPlan } from './playback';
import { tickToMs } from './timing';

function score(lines: Score['lines'], extra: Partial<Score> = {}): Score {
  return { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num: 4, den: 4 }], tempos: [{ bar: 0, bpm: 120 }], snap: '1/16', lines, ...extra };
}

describe('playbackPlan', () => {
  it('clicks every beat of the bars holding notes, downbeats marked', () => {
    const plan = playbackPlan(score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 200, d: 24, a: 2 }] }]));
    expect(plan.clicks.map((c) => [c.ms, c.downbeat])).toEqual([
      [0, true],
      [500, false],
      [1000, false],
      [1500, false],
      [2000, true],
      [2500, false],
      [3000, false],
      [3500, false],
    ]);
    expect(plan.endMs).toBe(4000);
  });

  it('plays each note with its duration and an accent gain', () => {
    const plan = playbackPlan(score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 48, d: 48, a: 2 }] }]));
    expect(plan.notes).toEqual([
      { ms: 0, durationMs: 250, gain: ACCENT_GAIN[0], line: 0, s: 0 },
      { ms: 500, durationMs: 500, gain: ACCENT_GAIN[2], line: 0, s: 1 },
    ]);
  });

  it('starts from a tick and follows tempo changes', () => {
    const s = score([{ notes: [{ s: 0, t: 200, d: 24, a: 1 }] }], {
      tempos: [
        { bar: 0, bpm: 120 },
        { bar: 1, bpm: 60 },
      ],
    });
    const plan = playbackPlan(s, 192);
    expect(plan.notes[0].ms).toBeCloseTo(tickToMs(s, 200) - tickToMs(s, 192), 9);
    expect(plan.clicks[0]).toEqual({ ms: 0, downbeat: true });
    expect(plan.clicks[1].ms).toBe(1000);
  });

  it('clicks the beats of a pickup', () => {
    const plan = playbackPlan(score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }] }], { pickup: 48 }));
    expect(plan.clicks[0]).toEqual({ ms: 0, downbeat: false });
    expect(plan.clicks[1]).toEqual({ ms: 500, downbeat: true });
  });
});

describe('msToTick', () => {
  it('inverts tickToMs across tempo changes', () => {
    const s = score([], {
      tempos: [
        { bar: 0, bpm: 120 },
        { bar: 1, bpm: 60 },
        { bar: 3, bpm: 200 },
      ],
    });
    for (const tick of [0, 1, 96, 191, 192, 300, 576, 1000]) expect(msToTick(s, tickToMs(s, tick))).toBeCloseTo(tick, 6);
    expect(msToTick(s, -5)).toBe(0);
  });
});
