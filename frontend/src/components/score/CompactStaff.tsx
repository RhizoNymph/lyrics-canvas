/** Read-only compact staff for a list row (DOM/SVG twin of the Pixi card's staff). */
import { useMemo } from 'react';
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import { lineStarts, noteSegments, rowAtTick, syllableTexts } from '@/lib/score';
import StaffRowView from './StaffRowView';
import { compactLayout } from './compactStaff';
import { COMPACT_STAFF } from './staffGeometry';

interface Props {
  score: Score;
  grid: GridData | null;
}

export default function CompactStaff({ score, grid }: Props) {
  const layout = useMemo(() => compactLayout(score, COMPACT_STAFF.rowWidth), [score]);
  const segments = useMemo(() => noteSegments(score, layout), [score, layout]);
  const labels = useMemo(() => syllableTexts(grid), [grid]);
  const marks = useMemo(() => {
    const out = layout.rows.map(() => [] as { line: number; tick: number }[]);
    for (const mark of lineStarts(score)) out[rowAtTick(layout, mark.tick)].push(mark);
    return out;
  }, [score, layout]);
  return (
    <div style={{ overflowX: 'auto' }} aria-label="Rhythm staff">
      {layout.rows.map((row, r) => (
        <div key={r} style={{ background: r % 2 === 0 ? 'rgba(36, 36, 72, 0.5)' : 'transparent', paddingLeft: 4 }}>
          <StaffRowView row={row} geometry={COMPACT_STAFF} scale={1} segments={segments[r]} labels={labels} lineMarks={marks[r]} selected={null} playhead={null} />
        </div>
      ))}
    </div>
  );
}
