/** View model for the status banner: one entry per thing worth telling the user. */

export type BannerTone = 'danger' | 'warning' | 'info' | 'success';

export type BannerSource = 'backend' | 'alert';

export interface BannerItem {
  /** Per-incident identity; dismissing hides exactly this key. */
  readonly key: string;
  readonly source: BannerSource;
  /** `alert` (assertive) for outages and critical alerts, `status` (polite) otherwise. */
  readonly role: 'alert' | 'status';
  readonly tone: BannerTone;
  readonly title: string;
  readonly detail: string | null;
  /** Long text, shown behind an expander. */
  readonly description: string | null;
  /** Epoch ms the incident started, when known. */
  readonly startedAt: number | null;
}

export interface BannerModel {
  /** Ordered: backend first, then alerts by severity (critical, warning, other), newest first. */
  readonly items: readonly BannerItem[];
  /** The alert list shown is the last known one; the alert source cannot be read right now. */
  readonly alertsStale: boolean;
}
