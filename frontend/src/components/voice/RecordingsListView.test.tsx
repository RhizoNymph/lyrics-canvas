import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import RecordingsListView from './RecordingsListView';
import type { RecordingsListViewProps } from './RecordingsListView';
import type { RecordingResponse } from '@/lib/voice/types';

const noop = () => undefined;

const rec = (id: string, over: Partial<RecordingResponse> = {}): RecordingResponse => ({
  id,
  snippet_id: 's1',
  mime_type: 'audio/webm',
  duration_ms: 42_000,
  byte_size: 1000,
  transcript: 'first line\nsecond line',
  split_mode: 'taps',
  created_at: '2026-10-31T18:05:00Z',
  ...over,
});

function render(over: Partial<RecordingsListViewProps> = {}) {
  return renderToStaticMarkup(
    <RecordingsListView
      recordings={[rec('a'), rec('b', { duration_ms: 5_000, transcript: 'hello' })]}
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
      locale="en-GB"
      {...over}
    />,
  );
}

describe('RecordingsListView', () => {
  it('renders nothing without recordings, leaving the editor layout untouched', () => {
    expect(render({ recordings: [] })).toBe('');
  });

  it('collapsed, it is a single toggle line with the count', () => {
    const html = render({ expanded: false });
    expect(html).toContain('Recordings (2)');
    expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain('<li');
  });

  it('expanded, lists date, duration and a transcript preview with play and delete', () => {
    const html = render();
    expect(html).toContain('aria-expanded="true"');
    expect((html.match(/<li/g) ?? []).length).toBe(2);
    expect(html).toContain('2026');
    expect(html).toContain('0:42');
    expect(html).toContain('0:05');
    expect(html).toContain('first line / second line');
    expect(html).toContain('aria-label="Play recording"');
    expect(html).toContain('aria-label="Delete recording"');
  });

  it('shows pause for the playing recording only', () => {
    const html = render({ playback: { kind: 'playing', id: 'b' } });
    expect((html.match(/aria-label="Pause recording"/g) ?? []).length).toBe(1);
    expect((html.match(/aria-label="Play recording"/g) ?? []).length).toBe(1);
  });

  it('asks for confirmation before deleting and shows errors', () => {
    const html = render({ confirmingId: 'a', deleteError: 'Delete failed' });
    expect(html).toContain('Delete?');
    expect(html).toMatch(/>Yes<\/button>/);
    expect(html).toMatch(/>No<\/button>/);
    expect(html).toContain('role="alert"');
    expect(html).toContain('Delete failed');
  });

  it('shows a playback error on its row', () => {
    expect(render({ playback: { kind: 'error', id: 'a', message: 'Not found' } })).toContain('Not found');
  });
});
