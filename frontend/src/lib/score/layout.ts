/**
 * Staff layout: bars wrapped into rows of a given pixel width, with tick <-> x mapping, beat ticks, note
 * segments per row and line boundaries. Pure and linear in bars + notes, so long snippets lay out fast;
 * the views render row by row.
 */
import type { Score, ScoreMeter, ScoreTempo } from '@/types/score';
import { isCompound, meterUnit, PICKUP_BAR, barCount } from './timing';
import { meterBarLength } from './validate';

export interface StaffBar {
  /** -1 is the pickup. */
  readonly bar: number;
  readonly start: number;
  readonly length: number;
  /** The meter in force (the first one in the pickup). */
  readonly meter: ScoreMeter;
  /** A meter entry starts at this bar (bar 0 always). */
  readonly meterChange: boolean;
  /** The tempo entry starting at this bar, if any (bar 0 always has one). */
  readonly tempoChange: ScoreTempo | null;
}

export interface StaffRow {
  readonly index: number;
  readonly start: number;
  readonly end: number;
  readonly bars: readonly StaffBar[];
}

export interface StaffLayout {
  readonly rows: readonly StaffRow[];
  readonly pxPerTick: number;
  /** Pixel width of the widest row. */
  readonly width: number;
  readonly end: number;
}

export interface LayoutOptions {
  /** Row width in pixels; bars wrap to the next row past it (a wider bar gets a row of its own). */
  readonly width: number;
  readonly pxPerTick: number;
  /** Empty bars after the last note, room to drop syllables. Default 1. */
  readonly extraBars?: number;
  /** At least this many bars from bar 0. */
  readonly minBars?: number;
}

/** The pickup (when present) and bars 0 .. count-1, computed per meter segment. */
export function staffBars(score: Score, count: number): StaffBar[] {
  const bars: StaffBar[] = [];
  const tempoAt = new Map(score.tempos.map((t) => [t.bar, t]));
  if (score.pickup > 0) {
    bars.push({ bar: PICKUP_BAR, start: 0, length: score.pickup, meter: score.meters[0], meterChange: false, tempoChange: null });
  }
  let start = score.pickup;
  let mi = 0;
  for (let bar = 0; bar < count; bar++) {
    while (mi + 1 < score.meters.length && score.meters[mi + 1].bar <= bar) mi++;
    const meter = score.meters[mi];
    const length = meterBarLength(meter);
    bars.push({ bar, start, length, meter, meterChange: meter.bar === bar, tempoChange: tempoAt.get(bar) ?? null });
    start += length;
  }
  return bars;
}

export function layoutStaff(score: Score, options: LayoutOptions): StaffLayout {
  const count = Math.max(barCount(score) + (options.extraBars ?? 1), options.minBars ?? 1);
  const bars = staffBars(score, count);
  const rows: StaffRow[] = [];
  let current: StaffBar[] = [];
  let used = 0;
  let width = 0;
  const flush = () => {
    if (current.length === 0) return;
    const last = current[current.length - 1];
    rows.push({ index: rows.length, start: current[0].start, end: last.start + last.length, bars: current });
    width = Math.max(width, used);
    current = [];
    used = 0;
  };
  for (const bar of bars) {
    const w = bar.length * options.pxPerTick;
    if (current.length > 0 && used + w > options.width) flush();
    current.push(bar);
    used += w;
  }
  flush();
  const last = bars[bars.length - 1];
  return { rows, pxPerTick: options.pxPerTick, width, end: last.start + last.length };
}

/** The row holding `tick` (the last row for ticks past the end). Binary search. */
export function rowAtTick(layout: StaffLayout, tick: number): number {
  let lo = 0;
  let hi = layout.rows.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (layout.rows[mid].start <= tick) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

export function xOfTick(layout: StaffLayout, row: StaffRow, tick: number): number {
  return (tick - row.start) * layout.pxPerTick;
}

export function tickAtX(layout: StaffLayout, row: StaffRow, x: number): number {
  return row.start + x / layout.pxPerTick;
}

export interface BeatTick {
  readonly tick: number;
  /** 3 downbeat, 2 beat, 1 subdivision. */
  readonly weight: 1 | 2 | 3;
}

/** Downbeat, beats and subdivisions of one bar (for the pickup: the tail of a virtual full bar). */
export function beatTicks(bar: StaffBar): BeatTick[] {
  const unit = meterUnit(bar.meter);
  const [beat, sub] = isCompound(bar.meter) ? [3 * unit, unit] : [unit, unit / 2];
  const full = meterBarLength(bar.meter);
  const offset0 = bar.bar === PICKUP_BAR ? full - bar.length : 0;
  const out: BeatTick[] = [];
  for (let off = Math.ceil(offset0 / sub) * sub; off < offset0 + bar.length; off += sub) {
    const weight = off === 0 ? 3 : off % beat === 0 ? 2 : 1;
    out.push({ tick: bar.start + off - offset0, weight });
  }
  return out;
}

export interface NoteSegment {
  readonly line: number;
  readonly s: number;
  readonly t: number;
  readonly d: number;
  readonly a: number;
  /** Visible span in this row. */
  readonly from: number;
  readonly to: number;
  /** The row holds the note's start (draw the head, accent and syllable here). */
  readonly head: boolean;
}

/** Each row's note segments; a note crossing rows continues as a bar in the next row. */
export function noteSegments(score: Score, layout: StaffLayout): NoteSegment[][] {
  const out: NoteSegment[][] = layout.rows.map(() => []);
  score.lines.forEach((line, li) => {
    for (const note of line.notes) {
      const end = note.t + note.d;
      for (let r = rowAtTick(layout, note.t); r < layout.rows.length; r++) {
        const row = layout.rows[r];
        if (row.start >= end) break;
        const from = Math.max(note.t, row.start);
        const to = Math.min(end, r === layout.rows.length - 1 ? end : row.end);
        out[r].push({ line: li, s: note.s, t: note.t, d: note.d, a: note.a, from, to, head: from === note.t });
        if (to >= end) break;
      }
    }
  });
  return out;
}

/** Where each grid line with notes starts on the staff (its first note). */
export function lineStarts(score: Score): { line: number; tick: number }[] {
  const out: { line: number; tick: number }[] = [];
  score.lines.forEach((line, i) => {
    if (line.notes.length > 0) out.push({ line: i, tick: line.notes[0].t });
  });
  return out;
}
