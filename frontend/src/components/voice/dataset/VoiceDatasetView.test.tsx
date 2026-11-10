import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import VoiceDatasetView from './VoiceDatasetView';
import type { VoiceDatasetViewProps } from './VoiceDatasetView';
import { VoiceError } from '@/lib/voice/types';

const noop = () => undefined;
const stats = { total: 15, verified: 12, total_duration_ms: 400_000, verified_duration_ms: 250_000, with_timings: 9 };

function render(over: Partial<VoiceDatasetViewProps> = {}) {
  return renderToStaticMarkup(
    <VoiceDatasetView
      stats={{ kind: 'loaded', stats }}
      download={{ kind: 'idle' }}
      includeUnverified={false}
      onIncludeUnverifiedChange={noop}
      onDownload={noop}
      onClose={noop}
      {...over}
    />,
  );
}

describe('VoiceDatasetView', () => {
  it('is a labelled modal dialog', () => {
    const html = render();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('Voice dataset');
  });

  it('shows the stats line', () => {
    expect(render()).toContain('12 of 15 takes checked · 4m 10s checked audio · 9 with word timings');
  });

  it('shows loading and failed stats', () => {
    expect(render({ stats: { kind: 'loading' } })).toContain('Loading');
    const failed = render({ stats: { kind: 'failed', error: new VoiceError('network', 'x') } });
    expect(failed).toContain('role="alert"');
    expect(failed).toContain('Could not reach the server');
  });

  it('has the download button and the include unchecked checkbox', () => {
    const html = render();
    expect(html).toContain('Download dataset (.zip)');
    expect(html).toContain('Include unchecked takes');
    expect(html).toMatch(/type="checkbox"/);
    expect(html).not.toMatch(/type="checkbox"[^>]*checked=""/);
    expect(render({ includeUnverified: true })).toMatch(/type="checkbox"[^>]*checked=""/);
  });

  it('disables the button while downloading', () => {
    const html = render({ download: { kind: 'downloading' } });
    expect(html).toMatch(/disabled=""[^>]*>Preparing…/);
  });

  it('shows a typed export error', () => {
    const html = render({ download: { kind: 'failed', error: new VoiceError('export_too_large', 'x', 413) } });
    expect(html).toContain('The dataset is too large to export');
    expect(html).toContain('Export only checked takes');
  });

  it('confirms the saved file name', () => {
    expect(render({ download: { kind: 'done', filename: 'a.zip' } })).toContain('a.zip');
  });

  it('warns when the export would be empty', () => {
    const none = { ...stats, verified: 0, verified_duration_ms: 0 };
    expect(render({ stats: { kind: 'loaded', stats: none } })).toContain('No checked takes yet');
    expect(render({ stats: { kind: 'loaded', stats: none }, includeUnverified: true })).not.toContain('No checked takes yet');
  });
});
