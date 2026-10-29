import { AlertFetchError } from './types';
import type { AlertFetchResult } from './types';

/** Poll interval while the endpoint exists (alerts, none, or temporarily unavailable). */
export const ALERTS_POLL_MS = 30_000;
/** Poll interval once the endpoint is absent (older deployment, dev server): re-check rarely. */
export const ALERTS_ABSENT_POLL_MS = 300_000;

export interface AlertsPollerOptions {
  /** One `GET /_status/alerts`; null when cancelled. */
  fetch: () => Promise<AlertFetchResult | null>;
  onResult: (result: AlertFetchResult) => void;
}

export interface AlertsPoller {
  start(): void;
  stop(): void;
  pause(): void;
  /** Resume with an immediate fetch. */
  resume(): void;
  /** Fetch now unless one is in flight. */
  pollNow(): void;
}

export function createAlertsPoller({ fetch, onResult }: AlertsPollerOptions): AlertsPoller {
  let running = false;
  let paused = false;
  let inFlight = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let lastKind: AlertFetchResult['kind'] | null = null;
  let lastMalformed = 0;

  function clearTimer(): void {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  }

  function schedule(): void {
    clearTimer();
    if (!running || paused || inFlight) return;
    timer = setTimeout(run, lastKind === 'absent' ? ALERTS_ABSENT_POLL_MS : ALERTS_POLL_MS);
  }

  function finish(result: AlertFetchResult | null): void {
    inFlight = false;
    if (!running) return;
    if (result) {
      const malformed = result.kind === 'alerts' ? result.malformed : 0;
      if (malformed > 0 && malformed !== lastMalformed) {
        console.warn('cluster alerts: dropped malformed entries', { malformed });
      }
      lastMalformed = malformed;
      lastKind = result.kind;
      onResult(result);
    }
    schedule();
  }

  function run(): void {
    timer = null;
    if (!running || inFlight) return;
    inFlight = true;
    fetch().then(finish, (error: unknown) => {
      console.error('cluster alerts fetch threw', { error });
      const message = error instanceof Error ? error.message : String(error);
      finish({ kind: 'failed', error: new AlertFetchError('network', message) });
    });
  }

  return {
    start() {
      if (running) return;
      running = true;
      paused = false;
      run();
    },
    stop() {
      running = false;
      clearTimer();
    },
    pause() {
      paused = true;
      clearTimer();
    },
    resume() {
      if (!running || !paused) return;
      paused = false;
      clearTimer();
      run();
    },
    pollNow() {
      if (!running || inFlight) return;
      clearTimer();
      run();
    },
  };
}
