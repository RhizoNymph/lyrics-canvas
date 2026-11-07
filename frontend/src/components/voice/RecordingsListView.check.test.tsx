import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import RecordingsListView from './RecordingsListView';
import type { RecordingResponse } from '@/lib/voice/types';

const noop = () => undefined;

const rec = (id: string, over: Partial<RecordingResponse> = {}): RecordingResponse => ({
  id,
  snippet_id: 's1',
  mime_type: 'audio/webm',
  duration_ms: 5000,
  byte_size: 10,
  transcript: 'hello',
  split_mode: 'taps',
  created_at: '2026-10-31T18:05:00Z',
  ...over,
});

function render(recordings: RecordingResponse[], onCheck?: (id: string) => void) {
  return renderToStaticMarkup(
    <RecordingsListView
      recordings={recordings}
      expanded
      playback={{ kind: 'idle' }}
      confirmingId={null}
      deletingId={null}
      deleteError={null}
      scale={1}
      onToggleExpanded={noop}
      onTogglePlay={noop}
      onAskDelete={noop}
      onConfirmDelete={noop}
      onCancelDelete={noop}
      onCheck={onCheck}
      locale="en-GB"
    />,
  );
}

describe('RecordingsListView checking', () => {
  it('badges checked and unchecked takes', () => {
    const html = render([
      rec('a', { verified_text: 'hello', verified_at: '2026-11-01T00:00:00Z' }),
      rec('b', { verified_text: null, verified_at: null }),
      rec('c'),
    ]);
    expect((html.match(/data-check-badge="checked"/g) ?? []).length).toBe(1);
    expect((html.match(/data-check-badge="unchecked"/g) ?? []).length).toBe(2);
    expect(html).toContain('>Checked<');
    expect(html).toContain('>Not checked<');
  });

  it('offers Check transcript on every row when wired', () => {
    const html = render([rec('a'), rec('b')], noop);
    expect((html.match(/aria-label="Check transcript"/g) ?? []).length).toBe(2);
  });

  it('has no check action without a handler', () => {
    expect(render([rec('a')])).not.toContain('aria-label="Check transcript"');
  });
});
