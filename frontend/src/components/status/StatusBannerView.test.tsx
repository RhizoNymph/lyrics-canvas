import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { StatusBannerView } from './StatusBannerView';
import type { BannerItem } from '@/lib/statusBanner/types';

function item(overrides: Partial<BannerItem>): BannerItem {
  return {
    key: 'k',
    source: 'alert',
    role: 'status',
    tone: 'warning',
    title: 'title',
    detail: null,
    description: null,
    startedAt: null,
    ...overrides,
  };
}

const backend = item({
  key: 'backend:down@1',
  source: 'backend',
  role: 'alert',
  tone: 'danger',
  title: 'Server is down',
  detail: 'Changes you make now may not be saving.',
  startedAt: Date.parse('2026-10-28T10:00:00Z'),
});
const critical = item({ key: 'alert:c', role: 'alert', tone: 'danger', title: 'Disk almost full', description: 'Volume at 97%.' });
const warning = item({ key: 'alert:w', title: 'High latency' });

function render(items: BannerItem[], alertsStale = false): string {
  return renderToStaticMarkup(<StatusBannerView items={items} alertsStale={alertsStale} onDismiss={() => undefined} />);
}

describe('StatusBannerView', () => {
  it('renders nothing when there is nothing to show', () => {
    expect(render([])).toBe('');
  });

  it('renders items in the given (priority) order: backend first, then alerts', () => {
    const html = render([backend, critical, warning]);
    const order = ['Server is down', 'Disk almost full', 'High latency'].map((t) => html.indexOf(t));
    expect(order.every((i) => i >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
  });

  it('uses role=alert for outages and critical alerts, role=status otherwise', () => {
    const html = render([backend, warning]);
    expect(html).toMatch(/role="alert"[^>]*>[\s\S]*Server is down/);
    expect(html).toMatch(/role="status"[^>]*>[\s\S]*High latency/);
  });

  it('shows the detail, start time and an expandable description', () => {
    const html = render([backend, critical]);
    expect(html).toContain('may not be saving');
    expect(html).toContain('<time');
    expect(html).toContain('dateTime="2026-10-28T10:00:00.000Z"');
    expect(html).toContain('<details');
    expect(html).toContain('Volume at 97%.');
  });

  it('has a labelled dismiss button per item', () => {
    const html = render([backend, warning]);
    expect(html).toContain('aria-label="Dismiss: Server is down"');
    expect(html).toContain('aria-label="Dismiss: High latency"');
  });

  it('notes when the alert list is out of date, only if alerts are shown', () => {
    expect(render([critical], true)).toMatch(/out of date/i);
    expect(render([backend], true)).not.toMatch(/out of date/i);
    expect(render([critical], false)).not.toMatch(/out of date/i);
  });
});
