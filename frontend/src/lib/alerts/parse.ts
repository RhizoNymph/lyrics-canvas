import axios from 'axios';
import { AlertFetchError } from './types';
import type { AlertFetchResult, AlertSeverity, AlertsState, ClusterAlert } from './types';

type JsonRecord = Record<string, unknown>;

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

function severityOf(value: unknown): AlertSeverity {
  return value === 'critical' || value === 'warning' ? value : 'other';
}

/** Stable identity from the string-valued labels, independent of key order. */
function labelsIdentity(labels: JsonRecord): string {
  return Object.keys(labels)
    .filter((k) => typeof labels[k] === 'string')
    .sort()
    .map((k) => `${k}=${String(labels[k])}`)
    .join(',');
}

type EntryOutcome = { kind: 'alert'; alert: ClusterAlert } | { kind: 'inactive' } | { kind: 'malformed' };

function parseEntry(entry: unknown): EntryOutcome {
  if (!isRecord(entry) || !isRecord(entry.labels) || !isRecord(entry.status)) return { kind: 'malformed' };
  const name = nonEmptyString(entry.labels.alertname);
  const state = entry.status.state;
  const startsAt = typeof entry.startsAt === 'string' ? Date.parse(entry.startsAt) : Number.NaN;
  if (name === null || typeof state !== 'string' || Number.isNaN(startsAt)) return { kind: 'malformed' };
  if (state !== 'active') return { kind: 'inactive' };

  const annotations = isRecord(entry.annotations) ? entry.annotations : {};
  const severity = severityOf(entry.labels.severity);
  const identity = nonEmptyString(entry.fingerprint) ?? labelsIdentity(entry.labels);
  return {
    kind: 'alert',
    alert: {
      key: `${identity}@${startsAt}#${severity}`,
      name,
      severity,
      summary: nonEmptyString(annotations.summary),
      description: nonEmptyString(annotations.description),
      startsAt,
    },
  };
}

/**
 * Validate an Alertmanager v2 alert list into firing alerts. Returns null when `data` is not an
 * array. Non-active entries (suppressed, unprocessed) are skipped; invalid entries are counted in
 * `malformed` and dropped.
 */
export function parseAlerts(data: unknown): { alerts: ClusterAlert[]; malformed: number } | null {
  if (!Array.isArray(data)) return null;
  const alerts: ClusterAlert[] = [];
  let malformed = 0;
  for (const entry of data) {
    const outcome = parseEntry(entry);
    if (outcome.kind === 'alert') alerts.push(outcome.alert);
    else if (outcome.kind === 'malformed') malformed += 1;
  }
  return { alerts, malformed };
}

type JsonParse = { ok: true; value: unknown } | { ok: false };

function parseJson(text: string): JsonParse {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (err) {
    if (err instanceof SyntaxError) return { ok: false };
    throw err;
  }
}

/** Interpret an HTTP answer from `GET /_status/alerts` (body as text). */
export function interpretAlertsResponse(status: number, body: string): AlertFetchResult {
  if (status === 200) {
    const json = parseJson(body);
    const parsed = json.ok ? parseAlerts(json.value) : null;
    // Not a JSON array: the SPA's index.html fallback of a deployment without the endpoint.
    if (parsed === null) return { kind: 'absent' };
    return { kind: 'alerts', alerts: parsed.alerts, malformed: parsed.malformed };
  }
  if (status === 404) return { kind: 'absent' };
  if (status >= 500) return { kind: 'unavailable', status };
  return { kind: 'failed', error: new AlertFetchError('http', `unexpected status ${status}`, status) };
}

/** Classify a thrown alert fetch. Null for a cancellation (no result). */
export function alertFetchFailure(err: unknown): AlertFetchResult | null {
  if (axios.isCancel(err)) return null;
  if (axios.isAxiosError(err)) {
    if (err.response) {
      const data: unknown = err.response.data;
      return interpretAlertsResponse(err.response.status, typeof data === 'string' ? data : JSON.stringify(data ?? ''));
    }
    const timedOut = err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT';
    return { kind: 'failed', error: new AlertFetchError(timedOut ? 'timeout' : 'network', err.message) };
  }
  const message = err instanceof Error ? err.message : String(err);
  return { kind: 'failed', error: new AlertFetchError('network', message) };
}

function lastKnownAlerts(state: AlertsState): readonly ClusterAlert[] {
  switch (state.kind) {
    case 'known':
      return state.alerts;
    case 'stale':
      return state.lastKnown;
    case 'idle':
    case 'absent':
      return [];
  }
}

/** Fold one fetch result into the alerts state. Failures keep the last known alerts, marked stale. */
export function applyAlertFetch(state: AlertsState, result: AlertFetchResult, now: number): AlertsState {
  switch (result.kind) {
    case 'alerts':
      return { kind: 'known', alerts: result.alerts, fetchedAt: now };
    case 'absent':
      return { kind: 'absent' };
    case 'unavailable':
      return { kind: 'stale', lastKnown: lastKnownAlerts(state), reason: 'unavailable' };
    case 'failed':
      return { kind: 'stale', lastKnown: lastKnownAlerts(state), reason: 'failed' };
  }
}
