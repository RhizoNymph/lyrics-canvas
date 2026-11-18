import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import StaffEditor from './StaffEditor';
import CompactStaff from './CompactStaff';
import { parseSyllableDrag, syllableDragData } from './ScoreTray';
import type { Playback } from './usePlayback';

const noop = () => undefined;
const idle: Playback = { playing: false, playheadTick: null, play: noop, stop: noop };

const grid: GridData = {
  lines: [
    {
      cells: [
        { syllable: 'hel', stress: 1, word_index: 0, word: 'hello' },
        { syllable: 'lo', stress: 0, word_index: 0, word: 'hello' },
        { syllable: 'world', stress: 1, word_index: 1, word: 'world' },
      ],
      syllable_count: 3,
      raw_text: 'hello world',
    },
    { cells: [{ syllable: 'now', stress: 1, word_index: 0, word: 'now' }], syllable_count: 1, raw_text: 'now' },
  ],
};

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 24,
  meters: [
    { bar: 0, num: 4, den: 4 },
    { bar: 1, num: 6, den: 8 },
  ],
  tempos: [
    { bar: 0, bpm: 90 },
    { bar: 1, bpm: 120 },
  ],
  snap: '1/16',
  lines: [{ notes: [{ s: 0, t: 0, d: 24, a: 1 }, { s: 1, t: 24, d: 48, a: 2 }] }, { notes: [] }],
};

describe('StaffEditor', () => {
  const html = renderToStaticMarkup(<StaffEditor score={score} grid={grid} scale={1} playback={idle} onCommit={noop} onRemoveScore={noop} />);

  it('draws placed syllables with accent marks and bar labels', () => {
    expect(html).toContain('>hel<');
    expect(html).toContain('>lo<');
    expect(html).toContain('&gt;&gt;');
    expect(html).toContain('pickup');
    expect(html).toContain('6/8');
    expect(html).toContain('♩=120');
  });

  it('lists the unplaced syllables per line in the tray', () => {
    expect(html).toContain('Unplaced syllables');
    expect(html).toMatch(/L1<\/span><button[^>]*>world</);
    expect(html).toMatch(/L2<\/span><button[^>]*>now</);
  });

  it('offers play, snap, pickup, meter/tempo and remove', () => {
    for (const text of ['▶ Play', 'Snap', 'Pickup', 'Meter / tempo', 'Remove score']) expect(html).toContain(text);
  });
});

describe('CompactStaff', () => {
  it('renders the syllables without editing controls', () => {
    const html = renderToStaticMarkup(<CompactStaff score={score} grid={grid} />);
    expect(html).toContain('>hel<');
    expect(html).not.toContain('Remove score');
  });
});

describe('tray drag payload', () => {
  it('round-trips and rejects anything else', () => {
    expect(parseSyllableDrag(syllableDragData({ line: 2, s: 5 }))).toEqual({ line: 2, s: 5 });
    expect(parseSyllableDrag('')).toBeNull();
    expect(parseSyllableDrag('nope')).toBeNull();
    expect(parseSyllableDrag('{"line":"x","s":1}')).toBeNull();
  });
});
