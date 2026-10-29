import type { CSSProperties } from 'react';
import type { BannerItem, BannerTone } from '@/lib/statusBanner/types';

const TONE: Record<BannerTone, { background: string; border: string; accent: string }> = {
  danger: { background: '#3b1d24', border: '#f87171', accent: '#fca5a5' },
  warning: { background: '#3a2f17', border: '#f59e0b', accent: '#fcd34d' },
  info: { background: '#1e2440', border: '#6366f1', accent: '#a5b4fc' },
  success: { background: '#16322a', border: '#34d399', accent: '#6ee7b7' },
};

/** Bottom-centre overlay; only the cards take pointer events, so the canvas stays usable around them. */
const containerStyle: CSSProperties = {
  position: 'fixed',
  left: '50%',
  bottom: 16,
  transform: 'translateX(-50%)',
  width: 'min(560px, calc(100vw - 32px))',
  maxHeight: '45vh',
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  zIndex: 2000,
  pointerEvents: 'none',
};

const cardStyle: CSSProperties = {
  pointerEvents: 'auto',
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  padding: '8px 10px',
  borderRadius: 6,
  borderLeftWidth: 4,
  borderLeftStyle: 'solid',
  color: '#e0e0e0',
  fontSize: 13,
  boxShadow: '0 4px 14px rgba(0, 0, 0, 0.45)',
};

const dismissStyle: CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#aaa',
  cursor: 'pointer',
  fontSize: 16,
  lineHeight: 1,
  padding: '0 2px',
};

const mutedStyle: CSSProperties = { color: '#a0a0bb', fontSize: 11 };

const timeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

function StartedAt({ at }: { at: number }) {
  const date = new Date(at);
  return (
    <span style={mutedStyle}>
      Since <time dateTime={date.toISOString()}>{timeFormat.format(date)}</time>
    </span>
  );
}

function BannerCard({ item, onDismiss }: { item: BannerItem; onDismiss: (key: string) => void }) {
  const tone = TONE[item.tone];
  return (
    <div
      role={item.role}
      aria-live={item.role === 'alert' ? 'assertive' : 'polite'}
      style={{ ...cardStyle, background: tone.background, borderLeftColor: tone.border }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 600, color: tone.accent }}>{item.title}</div>
        {item.detail !== null && <div style={{ marginTop: 2 }}>{item.detail}</div>}
        {item.startedAt !== null && <StartedAt at={item.startedAt} />}
        {item.description !== null && (
          <details style={{ marginTop: 4 }}>
            <summary style={{ cursor: 'pointer', ...mutedStyle }}>Details</summary>
            <div style={{ marginTop: 4, whiteSpace: 'pre-wrap', fontSize: 12 }}>{item.description}</div>
          </details>
        )}
      </div>
      <button type="button" style={dismissStyle} aria-label={`Dismiss: ${item.title}`} title="Dismiss" onClick={() => onDismiss(item.key)}>
        ×
      </button>
    </div>
  );
}

export interface StatusBannerViewProps {
  /** Visible items, already in priority order. */
  items: readonly BannerItem[];
  alertsStale: boolean;
  onDismiss: (key: string) => void;
}

/** Presentational banner: one card per item, backend status first. Renders nothing when empty. */
export function StatusBannerView({ items, alertsStale, onDismiss }: StatusBannerViewProps) {
  if (items.length === 0) return null;
  const showsAlerts = items.some((item) => item.source === 'alert');
  return (
    <section aria-label="Service status" style={containerStyle}>
      {items.map((item) => (
        <BannerCard key={item.key} item={item} onDismiss={onDismiss} />
      ))}
      {alertsStale && showsAlerts && (
        <div style={{ ...mutedStyle, pointerEvents: 'auto', textAlign: 'center' }}>
          Cluster alerts can't be read right now; this list may be out of date.
        </div>
      )}
    </section>
  );
}
