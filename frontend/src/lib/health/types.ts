/**
 * Backend health as seen from the browser. In production nginx serves the SPA and keeps
 * answering when the backend is dead (then `/api/*` returns 502/504), so the frontend decides
 * "down" itself from probes of `GET /api/health` and from failures of ordinary API calls.
 */

/** Why the server answered but the backend is not healthy. */
export type DownCause =
  /** The proxy (or backend) answered with this HTTP status: 5xx, or an unexpected 4xx on the probe. */
  | { type: 'http'; status: number }
  /** `/api/health` answered 200 but not with `{"status":"ok"}` (for example the SPA's index.html). */
  | { type: 'invalid_body' };

/** Why no answer arrived at all: the user's device is offline, or the server host is unreachable. */
export type UnreachableReason = 'offline' | 'network' | 'timeout';

/** A problem worth telling the user about. */
export type HealthProblem =
  | { kind: 'down'; cause: DownCause }
  | { kind: 'unreachable'; reason: UnreachableReason };

/** One observation of the backend, from a probe or from an ordinary API call. */
export type HealthObservation = { kind: 'ok' } | HealthProblem;

/**
 * Health state machine.
 *
 * - `unknown`: nothing observed yet (app start).
 * - `ok`: the last observation was healthy.
 * - `suspect`: one failure seen; not shown yet, a confirming probe is scheduled soon.
 * - `failing`: confirmed (`failures >= FAILURE_THRESHOLD`, or the browser reported offline); shown.
 * - `recovered`: was `failing`, now healthy; a brief "back online" note is shown until `settle`.
 *
 * `since` on `failing` identifies the incident (when it was confirmed), and is kept while the
 * problem persists, so dismissing the banner lasts exactly for that incident.
 */
export type HealthState =
  | { phase: 'unknown' }
  | { phase: 'ok' }
  | { phase: 'suspect'; problem: HealthProblem; failures: number }
  | { phase: 'failing'; problem: HealthProblem; failures: number; since: number }
  | { phase: 'recovered'; at: number; downSince: number };

/** Where an observation came from. Passive failures alone never confirm an incident. */
export type ObservationSource = 'probe' | 'passive';

export type HealthEvent =
  | { type: 'observed'; source: ObservationSource; observation: HealthObservation; at: number }
  /** The browser fired `offline`: definitive, confirmed immediately. */
  | { type: 'browser_offline'; at: number }
  /** The "back online" note has been shown long enough. */
  | { type: 'settle' };
