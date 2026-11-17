import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ScoreExportMenu, { ScoreExportMenuView, scoreExportEntries } from './ScoreExportMenu';
import type { Score } from '@/types/score';

vi.mock('@/api/client', () => ({ scoreApi: { exportScore: vi.fn() } }));

const noop = () => undefined;
const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [],
};

describe('scoreExportEntries', () => {
  it('lists MIDI and MusicXML for a snippet with a score', () => {
    expect(scoreExportEntries({ id: 's1', score })).toEqual([
      { format: 'midi', label: 'Export MIDI (.mid)' },
      { format: 'musicxml', label: 'Export MusicXML (.musicxml)' },
    ]);
  });

  it('lists nothing without a score', () => {
    expect(scoreExportEntries({ id: 's1', score: null })).toEqual([]);
    expect(scoreExportEntries({ id: 's1' })).toEqual([]);
  });
});

describe('ScoreExportMenuView', () => {
  it('closed: only the Export button', () => {
    const html = renderToStaticMarkup(<ScoreExportMenuView open={false} busy={false} onToggle={noop} onPick={noop} />);
    expect(html).toContain('aria-haspopup="menu"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain('role="menuitem"');
  });

  it('open: both formats as menu items', () => {
    const html = renderToStaticMarkup(<ScoreExportMenuView open busy={false} onToggle={noop} onPick={noop} />);
    expect(html).toContain('aria-expanded="true"');
    expect(html.match(/role="menuitem"/g)).toHaveLength(2);
    expect(html).toContain('MIDI');
    expect(html).toContain('MusicXML');
  });

  it('busy: the button says so and is disabled', () => {
    const html = renderToStaticMarkup(<ScoreExportMenuView open={false} busy onToggle={noop} onPick={noop} />);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Exporting…<\/button>/);
  });
});

describe('ScoreExportMenu', () => {
  it('renders nothing for a snippet without a score', () => {
    expect(renderToStaticMarkup(<ScoreExportMenu snippet={{ id: 's1', score: null }} />)).toBe('');
  });

  it('renders the button for a snippet with a score', () => {
    expect(renderToStaticMarkup(<ScoreExportMenu snippet={{ id: 's1', score }} />)).toContain('Export');
  });
});
