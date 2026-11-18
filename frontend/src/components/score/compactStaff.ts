/**
 * The compact staff shown on canvas cards and list rows when a snippet's view is the staff: the same
 * layout as the editor at COMPACT_STAFF resolution, flattened into drawing primitives (no Pixi or DOM),
 * so the Pixi card and its tests share one geometry.
 */
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import { beatTicks, layoutStaff, noteSegments, syllableTexts } from '@/lib/score';
import type { StaffLayout } from '@/lib/score';
import { accentMark, COMPACT_STAFF } from './staffGeometry';
import type { StaffGeometry } from './staffGeometry';

export interface CompactFrame {
  /** Combined scale (canvas zoom * card scale). */
  readonly s: number;
  readonly left: number;
  readonly top: number;
}

export interface Segment {
  readonly x1: number;
  readonly y1: number;
  readonly x2: number;
  readonly y2: number;
}

export interface CompactStaffShapes {
  readonly staffLines: Segment[];
  readonly barLines: Segment[];
  readonly beatMarks: Segment[];
  readonly noteBars: { x: number; y: number; w: number; h: number; accent: number }[];
  readonly heads: { x: number; y: number; rx: number; ry: number; accent: number }[];
  readonly texts: { text: string; x: number; y: number; bold: boolean; accent: boolean }[];
  /** Total height in screen pixels. */
  readonly height: number;
}

/** Lay out `score` in rows of `width` unscaled pixels. */
export function compactLayout(score: Score, width: number, geometry: StaffGeometry = COMPACT_STAFF): StaffLayout {
  return layoutStaff(score, { width: Math.max(width, 48 * geometry.pxPerTick), pxPerTick: geometry.pxPerTick, extraBars: 0 });
}

export function compactStaffShapes(
  score: Score,
  grid: GridData | null,
  width: number,
  frame: CompactFrame,
  geometry: StaffGeometry = COMPACT_STAFF,
): CompactStaffShapes {
  const layout = compactLayout(score, width, geometry);
  const segments = noteSegments(score, layout);
  const labels = syllableTexts(grid);
  const { s, left, top } = frame;
  const px = geometry.pxPerTick * s;
  const rowH = geometry.rowHeight * s;
  const shapes: CompactStaffShapes = { staffLines: [], barLines: [], beatMarks: [], noteBars: [], heads: [], texts: [], height: layout.rows.length * rowH };
  layout.rows.forEach((row, r) => {
    const y = top + r * rowH + geometry.staffY * s;
    const x = (tick: number) => left + (tick - row.start) * px;
    shapes.staffLines.push({ x1: x(row.start), y1: y, x2: x(row.end), y2: y });
    for (const bar of row.bars) {
      shapes.barLines.push({ x1: x(bar.start), y1: y - 5 * s, x2: x(bar.start), y2: y + 5 * s });
      for (const beat of beatTicks(bar)) {
        if (beat.weight === 2) shapes.beatMarks.push({ x1: x(beat.tick), y1: y - 2.5 * s, x2: x(beat.tick), y2: y + 2.5 * s });
      }
    }
    shapes.barLines.push({ x1: x(row.end), y1: y - 5 * s, x2: x(row.end), y2: y + 5 * s });
    for (const seg of segments[r]) {
      const x0 = x(seg.from);
      shapes.noteBars.push({ x: x0, y: y - (geometry.barHeight * s) / 2, w: Math.max(1, (seg.to - seg.from) * px), h: geometry.barHeight * s, accent: seg.a });
      if (!seg.head) continue;
      shapes.heads.push({ x: x0 + geometry.headRx * s, y, rx: geometry.headRx * s, ry: geometry.headRy * s, accent: seg.a });
      const label = labels[seg.line]?.[seg.s] ?? '';
      if (label !== '') shapes.texts.push({ text: label, x: x0, y: top + r * rowH + (geometry.textY - geometry.fontSize) * s, bold: seg.a > 0, accent: false });
      if (seg.a > 0) shapes.texts.push({ text: accentMark(seg.a), x: x0, y: top + r * rowH + (geometry.accentY - geometry.fontSize) * s, bold: true, accent: true });
    }
  });
  return shapes;
}

/** True when the snippet shows its staff instead of its grid. */
export function showsStaff(snippet: { score?: Score | null; score_view?: boolean }): snippet is { score: Score; score_view: true } {
  return snippet.score_view === true && snippet.score != null;
}
