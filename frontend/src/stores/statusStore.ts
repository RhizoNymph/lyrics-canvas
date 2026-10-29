import { create } from 'zustand';
import { INITIAL_HEALTH_STATE } from '@/lib/health/machine';
import { buildBannerModel, pruneDismissed } from '@/lib/statusBanner/items';
import type { AlertsState } from '@/lib/alerts/types';
import type { HealthState } from '@/lib/health/types';

interface StatusState {
  /** Backend health, written only by the health monitor. */
  health: HealthState;
  /** Cluster alerts, written only by the alerts poller. */
  alerts: AlertsState;
  /** Banner item keys the user dismissed; pruned to the currently shown incidents. */
  dismissed: ReadonlySet<string>;

  setHealth: (health: HealthState) => void;
  setAlerts: (alerts: AlertsState) => void;
  dismiss: (key: string) => void;
}

function currentKeys(health: HealthState, alerts: AlertsState): string[] {
  return buildBannerModel(health, alerts).items.map((item) => item.key);
}

export const useStatusStore = create<StatusState>((set, get) => ({
  health: INITIAL_HEALTH_STATE,
  alerts: { kind: 'idle' },
  dismissed: new Set<string>(),

  setHealth: (health) => set((s) => ({ health, dismissed: pruneDismissed(s.dismissed, currentKeys(health, s.alerts)) })),
  setAlerts: (alerts) => set((s) => ({ alerts, dismissed: pruneDismissed(s.dismissed, currentKeys(s.health, alerts)) })),
  dismiss: (key) => {
    const { health, alerts, dismissed } = get();
    if (dismissed.has(key) || !currentKeys(health, alerts).includes(key)) return;
    set({ dismissed: new Set([...dismissed, key]) });
  },
}));
