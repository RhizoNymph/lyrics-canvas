/**
 * Cluster alerts from `GET /_status/alerts` (same origin, served by nginx from Alertmanager's v2
 * API). The payload is validated at runtime into these domain types; malformed entries are dropped.
 */

export type AlertSeverity = 'critical' | 'warning' | 'other';

/** A firing (Alertmanager state `active`) alert. */
export interface ClusterAlert {
  /**
   * Identity of this firing incident: Alertmanager fingerprint (or the alert name and labels when
   * absent), its start time and severity. A new firing or a severity change gets a new key.
   */
  readonly key: string;
  readonly name: string;
  readonly severity: AlertSeverity;
  readonly summary: string | null;
  readonly description: string | null;
  /** Epoch milliseconds. */
  readonly startsAt: number;
}

/** Why a fetch of the alert list failed without a usable answer. */
export type AlertFetchErrorKind = 'network' | 'timeout' | 'http';

export class AlertFetchError extends Error {
  readonly kind: AlertFetchErrorKind;
  /** HTTP status for `http`; null otherwise. */
  readonly status: number | null;

  constructor(kind: AlertFetchErrorKind, message: string, status: number | null = null) {
    super(message);
    this.name = 'AlertFetchError';
    this.kind = kind;
    this.status = status;
  }
}

/** Outcome of one `GET /_status/alerts`. */
export type AlertFetchResult =
  /** 200 with a JSON array; `[]` means none firing or not configured. */
  | { kind: 'alerts'; alerts: readonly ClusterAlert[]; malformed: number }
  /** 503 `{"error":"alertmanager_unavailable"}` (or another 5xx): status unknown right now. */
  | { kind: 'unavailable'; status: number }
  /** 404, or 200 that is not a JSON array (SPA index.html fallback): the endpoint does not exist here. */
  | { kind: 'absent' }
  /** No answer, or an unexpected HTTP status. */
  | { kind: 'failed'; error: AlertFetchError };

/**
 * What the app knows about cluster alerts.
 *
 * - `idle`: not fetched yet.
 * - `absent`: this deployment has no alerts endpoint; stay quiet.
 * - `known`: the latest list.
 * - `stale`: the last fetch did not produce a list; `lastKnown` is what was firing before.
 */
export type AlertsState =
  | { kind: 'idle' }
  | { kind: 'absent' }
  | { kind: 'known'; alerts: readonly ClusterAlert[]; fetchedAt: number }
  | { kind: 'stale'; lastKnown: readonly ClusterAlert[]; reason: 'unavailable' | 'failed' };
