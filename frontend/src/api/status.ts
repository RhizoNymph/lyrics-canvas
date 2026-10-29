import axios from 'axios';
import { alertFetchFailure, interpretAlertsResponse } from '@/lib/alerts/parse';
import { observationFromProbeError, observationFromProbeResponse } from '@/lib/health/classify';
import type { AlertFetchResult } from '@/lib/alerts/types';
import type { HealthObservation } from '@/lib/health/types';

/**
 * Status probes. A separate instance from `client.ts`: no auth header, no 401 redirect, and its
 * results do not feed the passive health channel. Every HTTP status resolves; only transport
 * failures reject.
 */
const statusHttp = axios.create({ timeout: 8_000, validateStatus: () => true });

function isOnline(): boolean {
  return typeof navigator === 'undefined' ? true : navigator.onLine;
}

/** `GET /api/health` on the backend (`VITE_API_URL` when set, else same origin). */
export function probeHealth(): Promise<HealthObservation | null> {
  return statusHttp
    .get<unknown>('/api/health', { baseURL: import.meta.env.VITE_API_URL || '' })
    .then(
      (res) => observationFromProbeResponse(res.status, res.data),
      (err: unknown) => observationFromProbeError(err, isOnline())
    );
}

/** `GET /_status/alerts`, always on the SPA's own origin (served by nginx, not the backend). */
export function fetchClusterAlerts(): Promise<AlertFetchResult | null> {
  return statusHttp
    .get<string>('/_status/alerts', { responseType: 'text', transformResponse: (data: unknown) => data })
    .then(
      (res) => interpretAlertsResponse(res.status, typeof res.data === 'string' ? res.data : ''),
      alertFetchFailure
    );
}
