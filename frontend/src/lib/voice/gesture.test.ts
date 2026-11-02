import { describe, it, expect, vi, afterEach } from 'vitest';
import { DOUBLE_TAP_WINDOW_MS, IDLE_GESTURE, reduceGesture } from './gesture';
import type { GestureEffect, GestureEvent, GestureState } from './gesture';

function run(events: GestureEvent[], start: GestureState = IDLE_GESTURE) {
  let state = start;
  const effects: GestureEffect[] = [];
  for (const e of events) {
    const out = reduceGesture(state, e);
    state = out.state;
    effects.push(...out.effects);
  }
  return { state, effects };
}

describe('reduceGesture', () => {
  it('a first tap goes pending at its pointerdown time and arms the window timer', () => {
    const { state, effects } = run([{ type: 'tap', at: 1000 }]);
    expect(state).toEqual({ kind: 'pending', tapAt: 1000 });
    expect(effects).toEqual([{ kind: 'arm_timer', delayMs: DOUBLE_TAP_WINDOW_MS }]);
  });

  it('commits the break at the first tap time once the window elapses', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'window_elapsed', at: 1300 },
    ]);
    expect(state).toEqual(IDLE_GESTURE);
    expect(effects).toContainEqual({ kind: 'commit_break', at: 1000 });
  });

  it('a second tap inside the window stops and drops the pending break', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'tap', at: 1299 },
    ]);
    expect(state).toEqual(IDLE_GESTURE);
    expect(effects).toContainEqual({ kind: 'stop' });
    expect(effects.some((e) => e.kind === 'commit_break')).toBe(false);
    expect(effects).toContainEqual({ kind: 'cancel_timer' });
  });

  it('a second tap exactly at the window edge is a new single tap (the first commits)', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'tap', at: 1300 },
    ]);
    expect(state).toEqual({ kind: 'pending', tapAt: 1300 });
    expect(effects).toEqual([
      { kind: 'arm_timer', delayMs: 300 },
      { kind: 'cancel_timer' },
      { kind: 'commit_break', at: 1000 },
      { kind: 'arm_timer', delayMs: 300 },
    ]);
  });

  it('re-arms for the remainder when the timer fires early', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'window_elapsed', at: 1250 },
    ]);
    expect(state).toEqual({ kind: 'pending', tapAt: 1000 });
    expect(effects[effects.length - 1]).toEqual({ kind: 'arm_timer', delayMs: 50 });
  });

  it('ignores a stray window_elapsed while idle', () => {
    expect(run([{ type: 'window_elapsed', at: 5 }])).toEqual({ state: IDLE_GESTURE, effects: [] });
  });

  it('flush commits a pending tap (Stop button / silence stop are not taps)', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'flush' },
    ]);
    expect(state).toEqual(IDLE_GESTURE);
    expect(effects).toEqual([
      { kind: 'arm_timer', delayMs: 300 },
      { kind: 'cancel_timer' },
      { kind: 'commit_break', at: 1000 },
    ]);
    expect(run([{ type: 'flush' }]).effects).toEqual([]);
  });

  it('reset drops a pending tap without committing it (cancel)', () => {
    const { state, effects } = run([
      { type: 'tap', at: 1000 },
      { type: 'reset' },
    ]);
    expect(state).toEqual(IDLE_GESTURE);
    expect(effects.some((e) => e.kind === 'commit_break')).toBe(false);
    expect(effects[effects.length - 1]).toEqual({ kind: 'cancel_timer' });
  });

  it('three quick taps: the first two stop, the third starts a fresh pending tap', () => {
    const { state, effects } = run([
      { type: 'tap', at: 0 },
      { type: 'tap', at: 100 },
      { type: 'tap', at: 200 },
    ]);
    expect(effects.filter((e) => e.kind === 'stop')).toHaveLength(1);
    expect(state).toEqual({ kind: 'pending', tapAt: 200 });
  });
});

describe('reduceGesture driven by real (fake) timers', () => {
  afterEach(() => vi.useRealTimers());

  /** A tiny driver like the session's: applies effects to setTimeout and a log. */
  function driver() {
    let state: GestureState = IDLE_GESTURE;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const log: Array<string> = [];
    const dispatch = (event: GestureEvent) => {
      const out = reduceGesture(state, event);
      state = out.state;
      for (const effect of out.effects) {
        switch (effect.kind) {
          case 'arm_timer':
            timer = setTimeout(() => dispatch({ type: 'window_elapsed', at: Date.now() }), effect.delayMs);
            break;
          case 'cancel_timer':
            if (timer) clearTimeout(timer);
            timer = null;
            break;
          case 'commit_break':
            log.push(`break@${effect.at}`);
            break;
          case 'stop':
            log.push('stop');
            break;
        }
      }
    };
    return { tap: () => dispatch({ type: 'tap', at: Date.now() }), log };
  }

  it('single taps commit after 300 ms; a double tap stops without a break', () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const d = driver();
    d.tap(); // t=0
    vi.advanceTimersByTime(299);
    expect(d.log).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(d.log).toEqual(['break@0']);

    vi.advanceTimersByTime(700); // t=1000
    d.tap();
    vi.advanceTimersByTime(150);
    d.tap(); // double tap at 1150
    vi.advanceTimersByTime(1000);
    expect(d.log).toEqual(['break@0', 'stop']);
  });
});
