import { describe, it, expect, vi, afterEach } from 'vitest';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { alertFetchFailure, applyAlertFetch, interpretAlertsResponse, parseAlerts } from './parse';
import { AlertFetchError } from './types';
import type { AlertsState, ClusterAlert } from './types';

function raw(overrides: Record<string, unknown> = {}) {
  return {
    labels: { alertname: 'BackendDown', severity: 'critical', namespace: 'lyrics' },
    annotations: { summary: 'Backend has no ready pods', description: 'All lyrics-canvas backend pods are not ready.' },
    startsAt: '2026-10-28T10:00:00.000Z',
    endsAt: '2026-10-28T11:00:00.000Z',
    fingerprint: 'abc123',
    status: { state: 'active', silencedBy: [], inhibitedBy: [] },
    ...overrides,
  };
}

afterEach(() => vi.restoreAllMocks());

describe('parseAlerts', () => {
  it('parses a valid active alert into a domain object', () => {
    const result = parseAlerts([raw()]);
    expect(result).toEqual({
      alerts: [
        {
          key: `abc123@${Date.parse('2026-10-28T10:00:00.000Z')}#critical`,
          name: 'BackendDown',
          severity: 'critical',
          summary: 'Backend has no ready pods',
          description: 'All lyrics-canvas backend pods are not ready.',
          startsAt: Date.parse('2026-10-28T10:00:00.000Z'),
        },
      ],
      malformed: 0,
    });
  });

  it('an empty array is no alerts', () => {
    expect(parseAlerts([])).toEqual({ alerts: [], malformed: 0 });
  });

  it('a non-array payload is not an alert list', () => {
    expect(parseAlerts({ error: 'x' })).toBeNull();
    expect(parseAlerts('<!DOCTYPE html>')).toBeNull();
    expect(parseAlerts(null)).toBeNull();
  });

  it('maps severities: critical, warning, anything else is other', () => {
    const sev = (severity: unknown) =>
      parseAlerts([raw({ labels: { alertname: 'A', severity } })])!.alerts[0].severity;
    expect(sev('critical')).toBe('critical');
    expect(sev('warning')).toBe('warning');
    expect(sev('info')).toBe('other');
    expect(sev(undefined)).toBe('other');
    expect(sev(3)).toBe('other');
  });

  it('skips non-active (suppressed, unprocessed) alerts without counting them malformed', () => {
    const result = parseAlerts([
      raw({ status: { state: 'suppressed' } }),
      raw({ status: { state: 'unprocessed' } }),
      raw({ fingerprint: 'live' }),
    ]);
    expect(result!.alerts.map((a) => a.name)).toEqual(['BackendDown']);
    expect(result!.malformed).toBe(0);
  });

  it('drops malformed entries and counts them, keeping the valid ones', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const result = parseAlerts([
      null,
      'nope',
      42,
      {},
      raw({ labels: { severity: 'critical' } }), // no alertname
      raw({ labels: { alertname: '' } }), // empty alertname
      raw({ labels: 'x' }),
      raw({ startsAt: 'not a date' }),
      raw({ startsAt: undefined }),
      raw({ status: undefined }),
      raw({ status: { state: 7 } }),
      raw({ fingerprint: 'ok1' }),
    ]);
    expect(result!.alerts).toHaveLength(1);
    expect(result!.malformed).toBe(11);
  });

  it('tolerates missing or wrong-typed annotations', () => {
    const a = parseAlerts([raw({ annotations: undefined }), raw({ fingerprint: 'f2', annotations: { summary: 5, description: '' } })])!;
    expect(a.alerts[0]).toMatchObject({ summary: null, description: null });
    expect(a.alerts[1]).toMatchObject({ summary: null, description: null });
  });

  it('without a fingerprint, builds a stable key from the labels (order-independent)', () => {
    const a = parseAlerts([raw({ fingerprint: undefined, labels: { alertname: 'X', b: '2', a: '1' } })])!.alerts[0];
    const b = parseAlerts([raw({ fingerprint: undefined, labels: { a: '1', alertname: 'X', b: '2' } })])!.alerts[0];
    expect(a.key).toBe(b.key);
    expect(a.key).toContain('X');
  });

  it('a new firing (new startsAt) or severity change yields a new key', () => {
    const base = parseAlerts([raw()])!.alerts[0].key;
    const refired = parseAlerts([raw({ startsAt: '2026-10-28T12:00:00Z' })])!.alerts[0].key;
    const escalated = parseAlerts([raw({ labels: { alertname: 'BackendDown', severity: 'warning' } })])!.alerts[0].key;
    expect(refired).not.toBe(base);
    expect(escalated).not.toBe(base);
  });
});

describe('interpretAlertsResponse', () => {
  it('200 with a JSON array is the alert list', () => {
    const r = interpretAlertsResponse(200, JSON.stringify([raw()]));
    expect(r.kind).toBe('alerts');
    if (r.kind === 'alerts') expect(r.alerts).toHaveLength(1);
  });

  it('200 [] is no alerts', () => {
    expect(interpretAlertsResponse(200, '[]')).toEqual({ kind: 'alerts', alerts: [], malformed: 0 });
  });

  it('503 alertmanager_unavailable is unavailable', () => {
    expect(interpretAlertsResponse(503, '{"error":"alertmanager_unavailable"}')).toEqual({ kind: 'unavailable', status: 503 });
  });

  it('other 5xx (proxy errors) are unavailable too', () => {
    expect(interpretAlertsResponse(502, '<html>bad gateway</html>')).toEqual({ kind: 'unavailable', status: 502 });
  });

  it('404 is absent (older deployment, dev server)', () => {
    expect(interpretAlertsResponse(404, 'Not Found')).toEqual({ kind: 'absent' });
  });

  it('200 html (SPA fallback) or non-array json is absent', () => {
    expect(interpretAlertsResponse(200, '<!DOCTYPE html><html></html>')).toEqual({ kind: 'absent' });
    expect(interpretAlertsResponse(200, '{"a":1}')).toEqual({ kind: 'absent' });
    expect(interpretAlertsResponse(200, '')).toEqual({ kind: 'absent' });
  });

  it('an unexpected status is a typed http failure', () => {
    const r = interpretAlertsResponse(403, 'forbidden');
    expect(r.kind).toBe('failed');
    if (r.kind === 'failed') expect(r.error).toMatchObject({ name: 'AlertFetchError', kind: 'http', status: 403 });
  });
});

describe('alertFetchFailure', () => {
  const config = { headers: new AxiosHeaders() };

  it('a network error is failed: network', () => {
    const r = alertFetchFailure(new AxiosError('Network Error', 'ERR_NETWORK', config));
    expect(r?.kind === 'failed' && r.error.kind).toBe('network');
  });

  it('a timeout is failed: timeout', () => {
    const r = alertFetchFailure(new AxiosError('timeout', 'ECONNABORTED', config));
    expect(r?.kind === 'failed' && r.error.kind).toBe('timeout');
  });

  it('an error with a response is interpreted like a response', () => {
    const err = new AxiosError('x', 'E', config, undefined, { status: 404, statusText: '', headers: {}, config, data: '' });
    expect(alertFetchFailure(err)).toEqual({ kind: 'absent' });
  });

  it('a cancellation yields null (not a result)', () => {
    expect(alertFetchFailure(new CanceledError())).toBeNull();
  });

  it('anything else is failed: network with its message', () => {
    const r = alertFetchFailure(new Error('weird'));
    expect(r).toMatchObject({ kind: 'failed', error: { kind: 'network', message: 'weird' } });
  });
});

describe('applyAlertFetch', () => {
  const alert: ClusterAlert = { key: 'k', name: 'A', severity: 'warning', summary: null, description: null, startsAt: 1 };
  const known: AlertsState = { kind: 'known', alerts: [alert], fetchedAt: 5 };

  it('a list replaces the state', () => {
    expect(applyAlertFetch({ kind: 'idle' }, { kind: 'alerts', alerts: [alert], malformed: 0 }, 9)).toEqual({
      kind: 'known',
      alerts: [alert],
      fetchedAt: 9,
    });
  });

  it('absent is absent', () => {
    expect(applyAlertFetch(known, { kind: 'absent' }, 9)).toEqual({ kind: 'absent' });
  });

  it('unavailable keeps the last known alerts visible, marked stale', () => {
    expect(applyAlertFetch(known, { kind: 'unavailable', status: 503 }, 9)).toEqual({
      kind: 'stale',
      lastKnown: [alert],
      reason: 'unavailable',
    });
  });

  it('a network failure keeps the last known alerts too, and stays stale across failures', () => {
    const failed = { kind: 'failed' as const, error: new AlertFetchError('network', 'Network Error') };
    const s1 = applyAlertFetch(known, failed, 9);
    expect(s1).toEqual({ kind: 'stale', lastKnown: [alert], reason: 'failed' });
    expect(applyAlertFetch(s1, { kind: 'unavailable', status: 503 }, 10)).toEqual({ kind: 'stale', lastKnown: [alert], reason: 'unavailable' });
  });

  it('a failure with nothing known is stale with nothing to show', () => {
    expect(applyAlertFetch({ kind: 'idle' }, { kind: 'unavailable', status: 503 }, 9)).toEqual({ kind: 'stale', lastKnown: [], reason: 'unavailable' });
    expect(applyAlertFetch({ kind: 'absent' }, { kind: 'unavailable', status: 503 }, 9)).toEqual({ kind: 'stale', lastKnown: [], reason: 'unavailable' });
  });
});
