import { describe, it, expect, beforeEach } from 'vitest';
import { useStatusStore } from './statusStore';
import type { HealthState } from '@/lib/health/types';
import type { ClusterAlert } from '@/lib/alerts/types';

const failing = (since: number): HealthState => ({
  phase: 'failing',
  problem: { kind: 'down', cause: { type: 'http', status: 502 } },
  failures: 2,
  since,
});
const alert: ClusterAlert = { key: 'k1', name: 'A', severity: 'warning', summary: null, description: null, startsAt: 1 };

beforeEach(() => {
  useStatusStore.setState({ health: { phase: 'unknown' }, alerts: { kind: 'idle' }, dismissed: new Set() });
});

describe('statusStore', () => {
  it('dismiss hides the current incident only', () => {
    const s = useStatusStore.getState();
    s.setHealth(failing(100));
    s.dismiss('backend:down@100');
    expect(useStatusStore.getState().dismissed.has('backend:down@100')).toBe(true);
  });

  it('a new incident is not dismissed, and the old dismissal is forgotten', () => {
    const s = useStatusStore.getState();
    s.setHealth(failing(100));
    s.dismiss('backend:down@100');
    s.setHealth({ phase: 'recovered', at: 200, downSince: 100 });
    s.setHealth({ phase: 'ok' });
    s.setHealth(failing(300));
    expect([...useStatusStore.getState().dismissed]).toEqual([]);
  });

  it('a dismissed alert stays dismissed while it keeps firing', () => {
    const s = useStatusStore.getState();
    s.setAlerts({ kind: 'known', alerts: [alert], fetchedAt: 1 });
    s.dismiss('alert:k1');
    s.setAlerts({ kind: 'known', alerts: [alert], fetchedAt: 2 });
    expect(useStatusStore.getState().dismissed.has('alert:k1')).toBe(true);
    s.setAlerts({ kind: 'known', alerts: [], fetchedAt: 3 });
    expect(useStatusStore.getState().dismissed.size).toBe(0);
  });

  it('dismissing a key that is not shown does nothing', () => {
    useStatusStore.getState().dismiss('nope');
    expect(useStatusStore.getState().dismissed.size).toBe(0);
  });
});
