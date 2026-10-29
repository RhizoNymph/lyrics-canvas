import { describe, it, expect } from 'vitest';
import { buildBannerModel, pruneDismissed, visibleItems } from './items';
import type { ClusterAlert, AlertsState } from '@/lib/alerts/types';
import type { HealthState } from '@/lib/health/types';

function alert(key: string, severity: ClusterAlert['severity'], startsAt: number, extra: Partial<ClusterAlert> = {}): ClusterAlert {
  return { key, name: `Alert-${key}`, severity, summary: `summary ${key}`, description: null, startsAt, ...extra };
}

const known = (alerts: ClusterAlert[]): AlertsState => ({ kind: 'known', alerts, fetchedAt: 0 });
const failingDown: HealthState = { phase: 'failing', problem: { kind: 'down', cause: { type: 'http', status: 502 } }, failures: 2, since: 100 };

describe('buildBannerModel: backend', () => {
  it('is empty when healthy, unknown or merely suspect', () => {
    for (const h of [
      { phase: 'ok' },
      { phase: 'unknown' },
      { phase: 'suspect', problem: { kind: 'unreachable', reason: 'network' }, failures: 1 },
    ] as HealthState[]) {
      expect(buildBannerModel(h, { kind: 'idle' }).items).toEqual([]);
    }
  });

  it('down: an assertive danger item saying changes may not be saving', () => {
    const [item] = buildBannerModel(failingDown, { kind: 'idle' }).items;
    expect(item).toMatchObject({ source: 'backend', role: 'alert', tone: 'danger', startedAt: 100 });
    expect(item.title).toMatch(/server/i);
    expect(item.detail).toMatch(/502/);
    expect(item.detail).toMatch(/may not be sav/i);
  });

  it('an invalid health body still reads as down', () => {
    const h: HealthState = { ...failingDown, problem: { kind: 'down', cause: { type: 'invalid_body' } } };
    expect(buildBannerModel(h, { kind: 'idle' }).items[0].detail).toMatch(/may not be sav/i);
  });

  it('offline: names the device connection', () => {
    const h: HealthState = { phase: 'failing', problem: { kind: 'unreachable', reason: 'offline' }, failures: 2, since: 5 };
    const [item] = buildBannerModel(h, { kind: 'idle' }).items;
    expect(item.title).toMatch(/offline/i);
    expect(item.detail).toMatch(/may not be sav/i);
  });

  it('unreachable: says it may be the connection or the server host', () => {
    const h: HealthState = { phase: 'failing', problem: { kind: 'unreachable', reason: 'network' }, failures: 2, since: 5 };
    const [item] = buildBannerModel(h, { kind: 'idle' }).items;
    expect(item.title).toMatch(/can.t reach/i);
    expect(item.detail).toMatch(/connection/i);
    expect(item.role).toBe('alert');
  });

  it('down and unreachable are different incidents (different keys)', () => {
    const h2: HealthState = { ...failingDown, problem: { kind: 'unreachable', reason: 'network' } };
    expect(buildBannerModel(failingDown, { kind: 'idle' }).items[0].key).not.toBe(buildBannerModel(h2, { kind: 'idle' }).items[0].key);
  });

  it('recovered: a polite success "back online" item', () => {
    const [item] = buildBannerModel({ phase: 'recovered', at: 300, downSince: 100 }, { kind: 'idle' }).items;
    expect(item).toMatchObject({ source: 'backend', role: 'status', tone: 'success' });
    expect(item.title).toMatch(/back online/i);
  });
});

describe('buildBannerModel: alerts', () => {
  it('orders critical before warning before other, newest first within a severity', () => {
    const m = buildBannerModel(
      { phase: 'ok' },
      known([alert('w1', 'warning', 10), alert('o', 'other', 50), alert('c1', 'critical', 5), alert('c2', 'critical', 20), alert('w2', 'warning', 30)])
    );
    expect(m.items.map((i) => i.key)).toEqual(['alert:c2', 'alert:c1', 'alert:w2', 'alert:w1', 'alert:o']);
  });

  it('maps severity to tone and role', () => {
    const m = buildBannerModel({ phase: 'ok' }, known([alert('c', 'critical', 1), alert('w', 'warning', 1), alert('o', 'other', 1)]));
    expect(m.items.map((i) => [i.tone, i.role])).toEqual([
      ['danger', 'alert'],
      ['warning', 'status'],
      ['info', 'status'],
    ]);
  });

  it('uses the summary as title, falling back to the alert name; keeps description and start', () => {
    const m = buildBannerModel(
      { phase: 'ok' },
      known([alert('a', 'warning', 7, { summary: null, description: 'long text' }), alert('b', 'warning', 6)])
    );
    expect(m.items[0]).toMatchObject({ title: 'Alert-a', description: 'long text', startedAt: 7 });
    expect(m.items[1]).toMatchObject({ title: 'summary b', detail: 'Alert-b' });
  });

  it('the backend item comes first, and alerts stay visible while the backend is down', () => {
    const m = buildBannerModel(failingDown, known([alert('c', 'critical', 1)]));
    expect(m.items.map((i) => i.source)).toEqual(['backend', 'alert']);
  });

  it('stale state shows the last known alerts and flags them', () => {
    const m = buildBannerModel({ phase: 'ok' }, { kind: 'stale', lastKnown: [alert('c', 'critical', 1)], reason: 'unavailable' });
    expect(m.items).toHaveLength(1);
    expect(m.alertsStale).toBe(true);
  });

  it('stale with nothing known, absent and idle are quiet', () => {
    for (const a of [{ kind: 'stale', lastKnown: [], reason: 'failed' }, { kind: 'absent' }, { kind: 'idle' }] as AlertsState[]) {
      expect(buildBannerModel({ phase: 'ok' }, a)).toEqual({ items: [], alertsStale: false });
    }
  });

  it('known alerts are not stale', () => {
    expect(buildBannerModel({ phase: 'ok' }, known([alert('c', 'critical', 1)])).alertsStale).toBe(false);
  });
});

describe('dismissal', () => {
  it('hides dismissed keys only', () => {
    const m = buildBannerModel(failingDown, known([alert('c', 'critical', 1), alert('w', 'warning', 1)]));
    const backendKey = m.items[0].key;
    expect(visibleItems(m, new Set([backendKey, 'alert:w'])).map((i) => i.key)).toEqual(['alert:c']);
  });

  it('a dismissed backend incident reappears for a new incident', () => {
    const first = buildBannerModel(failingDown, { kind: 'idle' });
    const dismissed = new Set([first.items[0].key]);
    const later = buildBannerModel({ ...failingDown, since: 900 }, { kind: 'idle' });
    expect(visibleItems(later, dismissed)).toHaveLength(1);
  });

  it('a dismissed alert reappears when it re-fires (new key)', () => {
    const dismissed = new Set(['alert:k1']);
    expect(visibleItems(buildBannerModel({ phase: 'ok' }, known([alert('k1', 'warning', 1)])), dismissed)).toHaveLength(0);
    expect(visibleItems(buildBannerModel({ phase: 'ok' }, known([alert('k2', 'warning', 2)])), dismissed)).toHaveLength(1);
  });

  it('keeps the stale flag on the model when every alert is dismissed (the view decides)', () => {
    const m = buildBannerModel({ phase: 'ok' }, { kind: 'stale', lastKnown: [alert('c', 'critical', 1)], reason: 'failed' });
    expect(visibleItems(m, new Set(['alert:c']))).toEqual([]);
    expect(m.alertsStale).toBe(true);
  });

  it('pruneDismissed drops keys that are no longer current and keeps identity when unchanged', () => {
    const d = new Set(['a', 'b']);
    expect([...pruneDismissed(d, ['b', 'c'])]).toEqual(['b']);
    expect(pruneDismissed(d, ['a', 'b', 'c'])).toBe(d);
  });
});
