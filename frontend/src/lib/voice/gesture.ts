/**
 * Tap vs double-tap on the recording surface, as a pure reducer.
 *
 * A tap's time is taken at its pointerdown (or keydown). The first tap goes `pending`; if a second tap
 * arrives less than `DOUBLE_TAP_WINDOW_MS` after it, that is a double tap: stop, and the pending break
 * is dropped. Otherwise, once the window has elapsed, the break at the first tap's time is committed.
 * The Stop and Cancel buttons are not taps: Stop `flush`es (commits a pending tap), Cancel `reset`s.
 *
 * Times are on one monotonic clock (performance.now() in the browser). The driver turns effects into
 * timers and recorder events; the reducer never reads a clock itself.
 */
export const DOUBLE_TAP_WINDOW_MS = 300;

export type GestureState = { kind: 'idle' } | { kind: 'pending'; tapAt: number };

export const IDLE_GESTURE: GestureState = { kind: 'idle' };

export type GestureEvent =
  | { type: 'tap'; at: number }
  /** The armed timer fired; `at` is the clock when it did. */
  | { type: 'window_elapsed'; at: number }
  /** Commit a pending tap now (stop by button or silence). */
  | { type: 'flush' }
  /** Drop a pending tap (cancel). */
  | { type: 'reset' };

export type GestureEffect =
  | { kind: 'arm_timer'; delayMs: number }
  | { kind: 'cancel_timer' }
  | { kind: 'commit_break'; at: number }
  | { kind: 'stop' };

export interface GestureStep {
  state: GestureState;
  effects: GestureEffect[];
}

export function reduceGesture(state: GestureState, event: GestureEvent): GestureStep {
  switch (event.type) {
    case 'tap': {
      if (state.kind === 'idle') {
        return { state: { kind: 'pending', tapAt: event.at }, effects: [{ kind: 'arm_timer', delayMs: DOUBLE_TAP_WINDOW_MS }] };
      }
      if (event.at - state.tapAt < DOUBLE_TAP_WINDOW_MS) {
        return { state: IDLE_GESTURE, effects: [{ kind: 'cancel_timer' }, { kind: 'stop' }] };
      }
      // The timer has not fired yet but the window is over: the first tap was a single tap.
      return {
        state: { kind: 'pending', tapAt: event.at },
        effects: [
          { kind: 'cancel_timer' },
          { kind: 'commit_break', at: state.tapAt },
          { kind: 'arm_timer', delayMs: DOUBLE_TAP_WINDOW_MS },
        ],
      };
    }
    case 'window_elapsed': {
      if (state.kind === 'idle') return { state, effects: [] };
      const elapsed = event.at - state.tapAt;
      if (elapsed < DOUBLE_TAP_WINDOW_MS) {
        return { state, effects: [{ kind: 'arm_timer', delayMs: DOUBLE_TAP_WINDOW_MS - elapsed }] };
      }
      return { state: IDLE_GESTURE, effects: [{ kind: 'commit_break', at: state.tapAt }] };
    }
    case 'flush': {
      if (state.kind === 'idle') return { state, effects: [] };
      return { state: IDLE_GESTURE, effects: [{ kind: 'cancel_timer' }, { kind: 'commit_break', at: state.tapAt }] };
    }
    case 'reset': {
      return { state: IDLE_GESTURE, effects: [{ kind: 'cancel_timer' }] };
    }
  }
}
