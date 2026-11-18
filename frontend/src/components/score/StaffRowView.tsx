/**
 * One row of the staff as SVG: bar lines with numbers and meter/tempo changes, beat ticks, note heads with
 * length bars, accent marks, syllables, grid line boundaries and the playhead. Memoized with a content
 * comparison, so an edit re-renders only the rows it touches and the playhead only its own row.
 */
import { memo } from 'react';
import type { DragEvent as ReactDragEvent, PointerEvent as ReactPointerEvent } from 'react';
import type { NoteSegment, StaffRow } from '@/lib/score';
import { beatTicks } from '@/lib/score';
import { accentMark, STAFF_COLORS } from './staffGeometry';
import type { StaffGeometry } from './staffGeometry';

export interface NoteRef {
  readonly line: number;
  readonly s: number;
}

export type NoteGrab = 'move' | 'resize';

interface Props {
  row: StaffRow;
  geometry: StaffGeometry;
  scale: number;
  segments: readonly NoteSegment[];
  /** Per grid line, syllable text by ordinal. */
  labels: readonly (readonly string[])[];
  /** Grid lines whose first note starts in this row. */
  lineMarks: readonly { line: number; tick: number }[];
  selected: NoteRef | null;
  playhead: number | null;
  /** Editor only; the compact staff passes none. */
  interactive?: {
    onNotePointerDown: (e: ReactPointerEvent<SVGElement>, note: NoteRef, grab: NoteGrab) => void;
    onBarClick: (bar: number) => void;
    onStaffClick: (tick: number) => void;
    onDropSyllable: (tick: number, e: ReactDragEvent<SVGSVGElement>) => void;
  };
}

const RESIZE_GRIP = 6;

function StaffRowViewImpl({ row, geometry: g, scale: s, segments, labels, lineMarks, selected, playhead, interactive }: Props) {
  const px = g.pxPerTick * s;
  const width = (row.end - row.start) * px;
  const height = g.rowHeight * s;
  const x = (tick: number) => (tick - row.start) * px;
  const staffY = g.staffY * s;
  const font = g.fontSize * s;
  const showLabels = g.labelY > 0;

  const tickFromEvent = (clientX: number, target: SVGSVGElement) => {
    const rect = target.getBoundingClientRect();
    return row.start + (clientX - rect.left) / px;
  };

  return (
    <svg
      width={width}
      height={height}
      style={{ display: 'block', overflow: 'visible', cursor: interactive ? 'crosshair' : 'inherit' }}
      onClick={
        interactive
          ? (e) => {
              if (e.target === e.currentTarget) interactive.onStaffClick(tickFromEvent(e.clientX, e.currentTarget));
            }
          : undefined
      }
      onDragOver={interactive ? (e) => e.preventDefault() : undefined}
      onDrop={
        interactive
          ? (e) => {
              e.preventDefault();
              interactive.onDropSyllable(tickFromEvent(e.clientX, e.currentTarget), e);
            }
          : undefined
      }
    >
      <line x1={0} x2={width} y1={staffY} y2={staffY} stroke={STAFF_COLORS.staff} strokeWidth={1} pointerEvents="none" />
      {row.bars.map((bar) => (
        <g key={bar.bar} pointerEvents="none">
          {beatTicks(bar).map((beat) =>
            beat.weight === 3 ? null : (
              <line
                key={beat.tick}
                x1={x(beat.tick)}
                x2={x(beat.tick)}
                y1={staffY - (beat.weight === 2 ? 5 : 2.5) * s}
                y2={staffY + (beat.weight === 2 ? 5 : 2.5) * s}
                stroke={beat.weight === 2 ? STAFF_COLORS.beat : STAFF_COLORS.sub}
              />
            ),
          )}
          <line
            x1={x(bar.start)}
            x2={x(bar.start)}
            y1={staffY - 9 * s}
            y2={staffY + 9 * s}
            stroke={STAFF_COLORS.barLine}
            strokeDasharray={bar.bar < 0 ? `${2 * s} ${2 * s}` : undefined}
          />
        </g>
      ))}
      {showLabels &&
        row.bars.map((bar) => {
          const parts = [bar.bar < 0 ? 'pickup' : `${bar.bar + 1}`];
          if (bar.meterChange) parts.push(`${bar.meter.num}/${bar.meter.den}`);
          if (bar.tempoChange) parts.push(`♩=${bar.tempoChange.bpm}`);
          return (
            <text
              key={`label-${bar.bar}`}
              x={x(bar.start) + 3 * s}
              y={g.labelY * s}
              fontSize={10 * s}
              fill={bar.meterChange || bar.tempoChange ? STAFF_COLORS.text : STAFF_COLORS.label}
              fontFamily="monospace"
              style={{ cursor: interactive && bar.bar >= 0 ? 'pointer' : 'default', userSelect: 'none' }}
              onClick={interactive && bar.bar >= 0 ? () => interactive.onBarClick(bar.bar) : undefined}
            >
              {parts.join(' ')}
            </text>
          );
        })}
      {lineMarks.map((mark) => (
        <g key={`line-${mark.line}`} pointerEvents="none">
          <line
            x1={x(mark.tick) - 2 * s}
            x2={x(mark.tick) - 2 * s}
            y1={(showLabels ? g.labelY + 3 : 1) * s}
            y2={height - 2 * s}
            stroke={STAFF_COLORS.lineMark}
            strokeDasharray={`${3 * s} ${2 * s}`}
            opacity={0.7}
          />
          {showLabels && (
            <text x={x(mark.tick) - 1 * s} y={(g.accentY - 6) * s} fontSize={9 * s} fill={STAFF_COLORS.lineMark} fontFamily="monospace">
              L{mark.line + 1}
            </text>
          )}
        </g>
      ))}
      {segments.map((seg) => {
        const isSelected = selected !== null && selected.line === seg.line && selected.s === seg.s;
        const color = isSelected ? STAFF_COLORS.noteSelected : STAFF_COLORS.note;
        const x0 = x(seg.from);
        const x1 = Math.max(x0 + 1, x(seg.to));
        const ref = { line: seg.line, s: seg.s };
        const label = labels[seg.line]?.[seg.s] ?? '';
        const segmentEndsNote = seg.to === seg.t + seg.d;
        return (
          <g key={`${seg.line}-${seg.s}`}>
            <rect
              x={x0}
              y={staffY - (g.barHeight * s) / 2}
              width={x1 - x0}
              height={g.barHeight * s}
              fill={color}
              opacity={0.75}
              style={{ cursor: interactive ? 'grab' : 'inherit' }}
              onPointerDown={interactive ? (e) => interactive.onNotePointerDown(e, ref, 'move') : undefined}
            />
            {seg.head && (
              <ellipse
                cx={x0 + g.headRx * s}
                cy={staffY}
                rx={g.headRx * s}
                ry={g.headRy * s}
                fill={color}
                style={{ cursor: interactive ? 'grab' : 'inherit' }}
                onPointerDown={interactive ? (e) => interactive.onNotePointerDown(e, ref, 'move') : undefined}
              />
            )}
            {seg.head && seg.a > 0 && (
              <text x={x0} y={g.accentY * s} fontSize={font} fontWeight="bold" fill={STAFF_COLORS.accent} fontFamily="monospace" pointerEvents="none">
                {accentMark(seg.a)}
              </text>
            )}
            {seg.head && (
              <text
                x={x0}
                y={g.textY * s}
                fontSize={font}
                fill={isSelected ? STAFF_COLORS.noteSelected : STAFF_COLORS.text}
                fontFamily="monospace"
                fontWeight={seg.a > 0 ? 'bold' : 'normal'}
                pointerEvents="none"
              >
                {label}
              </text>
            )}
            {interactive && segmentEndsNote && (
              <rect
                x={x1 - RESIZE_GRIP * s}
                y={staffY - 7 * s}
                width={RESIZE_GRIP * s}
                height={14 * s}
                fill="transparent"
                style={{ cursor: 'ew-resize' }}
                onPointerDown={(e) => interactive.onNotePointerDown(e, ref, 'resize')}
              />
            )}
          </g>
        );
      })}
      {playhead !== null && (
        <line x1={x(playhead)} x2={x(playhead)} y1={0} y2={height} stroke={STAFF_COLORS.playhead} strokeWidth={1.5 * s} pointerEvents="none" />
      )}
    </svg>
  );
}

function sameSegments(a: readonly NoteSegment[], b: readonly NoteSegment[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  return a.every((x, i) => {
    const y = b[i];
    return x.line === y.line && x.s === y.s && x.t === y.t && x.d === y.d && x.a === y.a && x.from === y.from && x.to === y.to;
  });
}

function sameRow(a: StaffRow, b: StaffRow): boolean {
  if (a === b) return true;
  if (a.start !== b.start || a.end !== b.end || a.bars.length !== b.bars.length) return false;
  return a.bars.every((x, i) => {
    const y = b.bars[i];
    return (
      x.bar === y.bar &&
      x.start === y.start &&
      x.length === y.length &&
      x.meter.num === y.meter.num &&
      x.meter.den === y.meter.den &&
      x.meterChange === y.meterChange &&
      (x.tempoChange?.bpm ?? null) === (y.tempoChange?.bpm ?? null)
    );
  });
}

const StaffRowView = memo(StaffRowViewImpl, (prev, next) => {
  return (
    sameRow(prev.row, next.row) &&
    prev.geometry === next.geometry &&
    prev.scale === next.scale &&
    sameSegments(prev.segments, next.segments) &&
    prev.labels === next.labels &&
    prev.lineMarks.length === next.lineMarks.length &&
    prev.lineMarks.every((m, i) => m.line === next.lineMarks[i].line && m.tick === next.lineMarks[i].tick) &&
    (prev.selected?.line ?? -1) === (next.selected?.line ?? -1) &&
    (prev.selected?.s ?? -1) === (next.selected?.s ?? -1) &&
    prev.playhead === next.playhead &&
    prev.interactive === next.interactive
  );
});

export default StaffRowView;
