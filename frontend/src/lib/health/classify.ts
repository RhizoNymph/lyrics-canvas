import axios from 'axios';
import type { HealthObservation, UnreachableReason } from './types';

/** Statuses a reverse proxy (nginx) answers with when the backend behind it is dead or overloaded. */
const PROXY_DOWN_STATUSES: ReadonlySet<number> = new Set([502, 503, 504]);

function isHealthyBody(data: unknown): boolean {
  return typeof data === 'object' && data !== null && (data as { status?: unknown }).status === 'ok';
}

function unreachableReason(code: string | undefined, online: boolean): UnreachableReason {
  if (code === 'ECONNABORTED' || code === 'ETIMEDOUT') return 'timeout';
  return online ? 'network' : 'offline';
}

/** Classify an answer to `GET /api/health`. Anything but 200 `{"status":"ok"}` is down. */
export function observationFromProbeResponse(status: number, data: unknown): HealthObservation {
  if (status === 200) return isHealthyBody(data) ? { kind: 'ok' } : { kind: 'down', cause: { type: 'invalid_body' } };
  return { kind: 'down', cause: { type: 'http', status } };
}

/**
 * Classify a thrown probe failure. Returns null when it says nothing about the backend
 * (a cancellation, or a throwable that is not an axios error).
 */
export function observationFromProbeError(err: unknown, online: boolean): HealthObservation | null {
  if (axios.isCancel(err) || !axios.isAxiosError(err)) return null;
  if (err.response) return observationFromProbeResponse(err.response.status, err.response.data);
  return { kind: 'unreachable', reason: unreachableReason(err.code, online) };
}

/**
 * Classify a failed ordinary API call.
 *
 * - 502/503/504: the proxy answered for a dead backend (down).
 * - no response: unreachable.
 * - other 4xx: the backend itself answered, so it is up (ok).
 * - 500 and other 5xx: ambiguous (a backend bug, or the Vite dev proxy failing to connect), no signal.
 * - cancellations and non-axios throwables: no signal.
 */
export function passiveObservationFromError(err: unknown, online: boolean): HealthObservation | null {
  if (axios.isCancel(err) || !axios.isAxiosError(err)) return null;
  const response = err.response;
  if (!response) return { kind: 'unreachable', reason: unreachableReason(err.code, online) };
  if (PROXY_DOWN_STATUSES.has(response.status)) return { kind: 'down', cause: { type: 'http', status: response.status } };
  if (response.status >= 400 && response.status < 500) return { kind: 'ok' };
  return null;
}
