import type { AlertSeverity, AlertsState, ClusterAlert } from '@/lib/alerts/types';
import type { HealthProblem, HealthState } from '@/lib/health/types';
import type { BannerItem, BannerModel } from './types';

const NOT_SAVING = 'Changes you make now may not be saving.';

function problemText(problem: HealthProblem): { title: string; detail: string } {
  if (problem.kind === 'down') {
    const why =
      problem.cause.type === 'http'
        ? `The server answered with HTTP ${problem.cause.status}.`
        : "The server's health check returned an unexpected response.";
    return { title: 'Server is down', detail: `${why} ${NOT_SAVING}` };
  }
  switch (problem.reason) {
    case 'offline':
      return { title: "You're offline", detail: `This device has no network connection. ${NOT_SAVING}` };
    case 'timeout':
      return {
        title: "Can't reach the server",
        detail: `The server is not responding; check your connection. ${NOT_SAVING}`,
      };
    case 'network':
      return {
        title: "Can't reach the server",
        detail: `Check your connection; the server host may be unreachable. ${NOT_SAVING}`,
      };
  }
}

function backendItem(health: HealthState): BannerItem | null {
  switch (health.phase) {
    case 'unknown':
    case 'ok':
    case 'suspect':
      return null;
    case 'failing': {
      const { title, detail } = problemText(health.problem);
      return {
        // Down vs unreachable are separate incidents; the incident start keeps the key stable while it lasts.
        key: `backend:${health.problem.kind}@${health.since}`,
        source: 'backend',
        role: 'alert',
        tone: 'danger',
        title,
        detail,
        description: null,
        startedAt: health.since,
      };
    }
    case 'recovered':
      return {
        key: `backend:recovered@${health.at}`,
        source: 'backend',
        role: 'status',
        tone: 'success',
        title: 'Back online',
        detail: 'The server is reachable again. Changes made while it was down may not have been saved.',
        description: null,
        startedAt: null,
      };
  }
}

const SEVERITY_RANK: Record<AlertSeverity, number> = { critical: 0, warning: 1, other: 2 };

function alertItem(alert: ClusterAlert): BannerItem {
  const critical = alert.severity === 'critical';
  return {
    key: `alert:${alert.key}`,
    source: 'alert',
    role: critical ? 'alert' : 'status',
    tone: critical ? 'danger' : alert.severity === 'warning' ? 'warning' : 'info',
    title: alert.summary ?? alert.name,
    detail: alert.summary === null ? null : alert.name,
    description: alert.description,
    startedAt: alert.startsAt,
  };
}

function shownAlerts(alerts: AlertsState): readonly ClusterAlert[] {
  switch (alerts.kind) {
    case 'known':
      return alerts.alerts;
    case 'stale':
      return alerts.lastKnown;
    case 'idle':
    case 'absent':
      return [];
  }
}

/** Everything worth showing, in priority order: backend first, then alerts by severity, newest first. */
export function buildBannerModel(health: HealthState, alerts: AlertsState): BannerModel {
  const shown = [...shownAlerts(alerts)].sort(
    (a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || b.startsAt - a.startsAt
  );
  const backend = backendItem(health);
  const items = [...(backend ? [backend] : []), ...shown.map(alertItem)];
  return { items, alertsStale: alerts.kind === 'stale' && shown.length > 0 };
}

export function visibleItems(model: BannerModel, dismissed: ReadonlySet<string>): BannerItem[] {
  return model.items.filter((item) => !dismissed.has(item.key));
}

/** Forget dismissals of incidents that are over; returns `dismissed` itself when nothing changed. */
export function pruneDismissed(dismissed: ReadonlySet<string>, currentKeys: Iterable<string>): ReadonlySet<string> {
  const current = new Set(currentKeys);
  const kept = [...dismissed].filter((key) => current.has(key));
  return kept.length === dismissed.size ? dismissed : new Set(kept);
}
