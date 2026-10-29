import { INITIAL_HEALTH_STATE, RECOVERY_DISPLAY_MS, nextProbeDelay, transition } from './machine';
import type { HealthEvent, HealthObservation, HealthState, ObservationSource } from './types';

export interface HealthMonitorOptions {
  /** One `GET /api/health`; null when the probe carries no signal (cancelled). */
  probe: () => Promise<HealthObservation | null>;
  now: () => number;
  onChange: (state: HealthState) => void;
}

export interface HealthMonitor {
  /** Probe at once and keep polling. */
  start(): void;
  /** Stop for good: timers cleared, late results and observations ignored. */
  stop(): void;
  /** Stop polling (tab hidden); observations still apply. */
  pause(): void;
  /** Resume polling with an immediate probe. */
  resume(): void;
  /** Probe now unless one is in flight (visibility regained, `online` event). */
  probeNow(): void;
  observe(observation: HealthObservation, source: ObservationSource): void;
  /** The browser fired `offline`. */
  browserOffline(): void;
  state(): HealthState;
}

/**
 * Drives the health state machine with real timers: polls `probe` at the interval the current state
 * asks for (`nextProbeDelay`), applies passive observations, and ends the "back online" note after
 * `RECOVERY_DISPLAY_MS`.
 */
export function createHealthMonitor({ probe, now, onChange }: HealthMonitorOptions): HealthMonitor {
  let state: HealthState = INITIAL_HEALTH_STATE;
  let running = false;
  let paused = false;
  let inFlight = false;
  let probeTimer: ReturnType<typeof setTimeout> | null = null;
  let settleTimer: ReturnType<typeof setTimeout> | null = null;

  function clearProbeTimer(): void {
    if (probeTimer !== null) clearTimeout(probeTimer);
    probeTimer = null;
  }

  function clearSettleTimer(): void {
    if (settleTimer !== null) clearTimeout(settleTimer);
    settleTimer = null;
  }

  function schedule(): void {
    clearProbeTimer();
    if (!running || paused || inFlight) return;
    probeTimer = setTimeout(runProbe, nextProbeDelay(state));
  }

  function dispatch(event: HealthEvent): void {
    if (!running) return;
    const next = transition(state, event);
    if (next === state) return;
    const phaseChanged = next.phase !== state.phase;
    state = next;
    if (state.phase === 'recovered') {
      if (phaseChanged) {
        clearSettleTimer();
        settleTimer = setTimeout(() => dispatch({ type: 'settle' }), RECOVERY_DISPLAY_MS);
      }
    } else {
      clearSettleTimer();
    }
    onChange(state);
    // A passive observation can change how soon the next probe is due (e.g. confirm a suspect quickly).
    if (phaseChanged) schedule();
  }

  function finishProbe(observation: HealthObservation | null): void {
    inFlight = false;
    if (!running) return;
    if (observation) dispatch({ type: 'observed', source: 'probe', observation, at: now() });
    schedule();
  }

  function runProbe(): void {
    probeTimer = null;
    if (!running || inFlight) return;
    inFlight = true;
    probe().then(finishProbe, (error: unknown) => {
      console.error('health probe threw; counting it as unreachable', { error });
      finishProbe({ kind: 'unreachable', reason: 'network' });
    });
  }

  return {
    start() {
      if (running) return;
      running = true;
      paused = false;
      runProbe();
    },
    stop() {
      running = false;
      clearProbeTimer();
      clearSettleTimer();
    },
    pause() {
      paused = true;
      clearProbeTimer();
    },
    resume() {
      if (!running || !paused) return;
      paused = false;
      clearProbeTimer();
      runProbe();
    },
    probeNow() {
      if (!running || inFlight) return;
      clearProbeTimer();
      runProbe();
    },
    observe(observation, source) {
      dispatch({ type: 'observed', source, observation, at: now() });
    },
    browserOffline() {
      dispatch({ type: 'browser_offline', at: now() });
    },
    state: () => state,
  };
}
