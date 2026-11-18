/** Staff geometry for the editor and the compact (card / list row) staff. Pure constants. */

export interface StaffGeometry {
  /** Pixels per tick at scale 1. */
  readonly pxPerTick: number;
  /** Row width in pixels at scale 1 (bars wrap past it). */
  readonly rowWidth: number;
  readonly rowHeight: number;
  /** Baseline of bar numbers, meter and tempo labels. */
  readonly labelY: number;
  readonly accentY: number;
  readonly staffY: number;
  readonly textY: number;
  readonly headRx: number;
  readonly headRy: number;
  readonly barHeight: number;
  readonly fontSize: number;
}

/** The editor: a quarter note is 48 px, four 4/4 bars per row. */
export const EDITOR_STAFF: StaffGeometry = {
  pxPerTick: 1,
  rowWidth: 768,
  rowHeight: 78,
  labelY: 11,
  accentY: 26,
  staffY: 40,
  textY: 64,
  headRx: 5,
  headRy: 4,
  barHeight: 4,
  fontSize: 12,
};

/** Cards and list rows: half the editor's resolution, no bar labels. */
export const COMPACT_STAFF: StaffGeometry = {
  pxPerTick: 0.5,
  rowWidth: 600,
  rowHeight: 32,
  labelY: 0,
  accentY: 8,
  staffY: 14,
  textY: 28,
  headRx: 3,
  headRy: 2.5,
  barHeight: 2,
  fontSize: 9,
};

/** Default length of a syllable dropped from the tray (an eighth). */
export const DEFAULT_NOTE_TICKS = 24;

export const STAFF_COLORS = {
  staff: '#6a6a9a',
  barLine: '#8a8ab8',
  beat: '#4a4a6a',
  sub: '#33334f',
  note: '#a5b4fc',
  noteSelected: '#fbbf24',
  accent: '#f472b6',
  text: '#d0d0e8',
  label: '#7a7a9a',
  lineMark: '#34d399',
  playhead: '#f87171',
  rowBand: 'rgba(36, 36, 72, 0.5)',
} as const;

/** The accent mark drawn above a note: `>` accent, `>>` strong. */
export function accentMark(a: number): string {
  return a === 2 ? '>>' : a === 1 ? '>' : '';
}
