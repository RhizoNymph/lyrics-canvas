import type { GridLine } from '@/types';
import { CELL_WIDTH, CELL_HEIGHT } from '@/components/canvas/gridConstants';
import { cellLabel, cellTextKind, GRID_TEXT_COLORS, GRID_ROW_BAND, GRID_LINE_COLOR } from '@/components/canvas/gridCellText';

interface Props {
  lines: ReadonlyArray<GridLine>;
}

/**
 * Read-only DOM beat grid for a list row: the same cell geometry (gridConstants), labels, tones and row
 * banding as the Pixi card (gridDraw), at scale 1. Scrolls sideways inside the row when wide.
 */
export default function GridPreview({ lines }: Props) {
  if (lines.length === 0) return null;
  const maxCells = Math.max(...lines.map((l) => l.cells.length), 1);
  return (
    <div style={{ overflowX: 'auto' }}>
      <div style={{ display: 'inline-block', minWidth: maxCells * CELL_WIDTH, borderTop: `1px solid ${GRID_LINE_COLOR}` }}>
        {lines.map((line, row) => (
          <div
            key={row}
            style={{
              display: 'flex',
              alignItems: 'center',
              height: CELL_HEIGHT,
              background: row % 2 === 0 ? GRID_ROW_BAND : 'transparent',
              borderBottom: `1px solid ${GRID_LINE_COLOR}`,
            }}
          >
            {line.cells.map((cell, col) => {
              const kind = cellTextKind(cell);
              return (
                <span
                  key={col}
                  style={{
                    width: CELL_WIDTH,
                    height: CELL_HEIGHT,
                    flexShrink: 0,
                    boxSizing: 'border-box',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: kind === 'rest' ? 'center' : 'flex-start',
                    padding: '0 4px',
                    borderRight: `1px solid ${GRID_LINE_COLOR}`,
                    fontFamily: 'monospace',
                    fontSize: 13,
                    color: GRID_TEXT_COLORS[kind],
                    fontWeight: kind === 'stressed' ? 'bold' : 'normal',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {cellLabel(cell)}
                </span>
              );
            })}
            {Array.from({ length: maxCells - line.cells.length }, (_, i) => (
              <span key={`pad-${i}`} style={{ width: CELL_WIDTH, flexShrink: 0 }} />
            ))}
            <span
              style={{
                minWidth: 28,
                paddingLeft: 8,
                paddingRight: 4,
                textAlign: 'right',
                fontFamily: 'monospace',
                fontSize: 10,
                color: GRID_TEXT_COLORS.count,
                flexShrink: 0,
              }}
            >
              {line.syllable_count || ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
