import type { HealthEvent, HealthProblem, HealthState } from './types';

/** Consecutive failures needed before the banner says the backend is down. */
export const FAILURE_THRESHOLD = 2;
/** Probe interval while healthy (or not yet known). */
export const HEALTHY_PROBE_MS = 15_000;
/** Quick confirming probe after a first failure. */
export const SUSPECT_PROBE_MS = 3_000;
/** First retry delay once down; doubles per further failure up to the cap. */
export const DOWN_BACKOFF_BASE_MS = 5_000;
export const DOWN_BACKOFF_MAX_MS = 60_000;
/** How long "back online" stays up after a recovery. */
export const RECOVERY_DISPLAY_MS = 5_000;

export const INITIAL_HEALTH_STATE: HealthState = { phase: 'unknown' };

function onProblem(state: HealthState, problem: HealthProblem, confirming: boolean, at: number): HealthState {
  switch (state.phase) {
    case 'unknown':
    case 'ok':
    case 'recovered':
      return { phase: 'suspect', problem, failures: 1 };
    case 'suspect': {
      // Passive failures (ordinary API calls) never confirm on their own: a burst of parallel
      // requests failing together is one observation, so only a probe can confirm.
      if (!confirming) return state;
      const failures = state.failures + 1;
      return failures >= FAILURE_THRESHOLD
        ? { phase: 'failing', problem, failures, since: at }
        : { phase: 'suspect', problem, failures };
    }
    case 'failing':
      return confirming ? { ...state, problem, failures: state.failures + 1 } : state;
  }
}

/** Pure transition function of the health state machine. */
export function transition(state: HealthState, event: HealthEvent): HealthState {
  switch (event.type) {
    case 'observed': {
      const { observation, source, at } = event;
      if (observation.kind === 'ok') {
        if (state.phase === 'failing') return { phase: 'recovered', at, downSince: state.since };
        if (state.phase === 'recovered' || state.phase === 'ok') return state;
        return { phase: 'ok' };
      }
      return onProblem(state, observation, source === 'probe', at);
    }
    case 'browser_offline': {
      const problem: HealthProblem = { kind: 'unreachable', reason: 'offline' };
      if (state.phase === 'failing') return { ...state, problem };
      return { phase: 'failing', problem, failures: FAILURE_THRESHOLD, since: event.at };
    }
    case 'settle':
      return state.phase === 'recovered' ? { phase: 'ok' } : state;
  }
}

/** Delay before the next health probe in this state (backoff while down). */
export function nextProbeDelay(state: HealthState): number {
  switch (state.phase) {
    case 'unknown':
    case 'ok':
    case 'recovered':
      return HEALTHY_PROBE_MS;
    case 'suspect':
      return SUSPECT_PROBE_MS;
    case 'failing': {
      const extra = Math.max(0, state.failures - FAILURE_THRESHOLD);
      return Math.min(DOWN_BACKOFF_BASE_MS * 2 ** extra, DOWN_BACKOFF_MAX_MS);
    }
  }
}
