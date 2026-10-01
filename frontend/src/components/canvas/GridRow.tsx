import { useRef } from 'react';
import type { CSSProperties, MouseEvent as ReactMouseEvent } from 'react';
import type { GridCell } from '@/types';
import { CELL_WIDTH, CELL_HEIGHT } from './gridConstants';
import { useChunkDrag } from './useChunkDrag';
import type { ChunkSelection } from './useChunkDrag';

/** Width reserved right of the count badge for the per-row buttons (unscaled px). */
export const ROW_CONTROLS_WIDTH = 64;

export interface GridRowProps {
  cells: GridCell[];
  count: number;
  scale: number;
  /** Active rows get the highlight and the trailing empty cell. */
  active: boolean;
  selection: ChunkSelection | null;
  canMoveUp: boolean;
  canMoveDown: boolean;
  onSelectCell: (col: number, extend: boolean) => void;
  /** Double-click on a cell: open the row's text input. */
  onActivate: () => void;
  onMoveChunk: (start: number, end: number, to: number) => void;
  onMoveRow: (direction: -1 | 1) => void;
  onInsertRest: () => void;
  onRemoveRests: () => void;
}

const stop = (e: ReactMouseEvent) => e.stopPropagation();

function buttonStyle(scale: number, enabled: boolean, visible = true): CSSProperties {
  return {
    width: 14 * scale,
    height: 14 * scale,
    padding: 0,
    border: 'none',
    borderRadius: 2,
    background: 'transparent',
    color: enabled ? '#9090c8' : '#3a3a5a',
    fontSize: 9 * scale,
    lineHeight: 1,
    cursor: enabled ? 'pointer' : 'default',
    visibility: visible ? 'visible' : 'hidden',
    flexShrink: 0,
  };
}

/** One row of syllable cells with chunk selection, chunk drag, and the row buttons. */
export default function GridRow(props: GridRowProps) {
  const { cells, count, scale: s, active, selection, canMoveUp, canMoveDown } = props;
  const stripRef = useRef<HTMLDivElement>(null);
  const drag = useChunkDrag({
    cellWidth: CELL_WIDTH * s,
    cellCount: cells.length,
    selection,
    stripRef,
    onDrop: props.onMoveChunk,
  });

  const selectedAllRests =
    selection !== null && selection.end > selection.start && cells.slice(selection.start, selection.end).every((c) => c.rest);

  return (
    <div
      ref={stripRef}
      style={{ position: 'relative', display: 'flex', alignItems: 'center', height: CELL_HEIGHT * s, minWidth: 0 }}
    >
      {cells.map((cell, col) => {
        const selected = selection !== null && col >= selection.start && col < selection.end;
        return (
          <span
            key={col}
            onClick={(e) => {
              e.stopPropagation();
              if (drag.consumeDrag()) return;
              props.onSelectCell(col, e.shiftKey);
            }}
            onDoubleClick={(e) => {
              e.stopPropagation();
              props.onActivate();
            }}
            onPointerDown={(e) => drag.onPointerDown(e, col)}
            onPointerMove={drag.onPointerMove}
            onPointerUp={drag.onPointerUp}
            onPointerCancel={drag.onPointerCancel}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              width: CELL_WIDTH * s,
              height: CELL_HEIGHT * s,
              fontSize: 13 * s,
              fontFamily: 'monospace',
              color: cell.rest ? '#555577' : cell.stress && cell.stress > 0 ? '#ffffff' : '#d0d0e8',
              fontWeight: cell.stress && cell.stress > 0 ? 'bold' : 'normal',
              padding: `0 ${4 * s}px`,
              boxSizing: 'border-box',
              borderRight: '1px solid rgba(74, 74, 106, 0.35)',
              borderBottom: '1px solid rgba(74, 74, 106, 0.18)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              background: selected
                ? drag.dragging
                  ? 'rgba(99, 102, 241, 0.2)'
                  : 'rgba(99, 102, 241, 0.4)'
                : active
                  ? 'rgba(99, 102, 241, 0.06)'
                  : 'transparent',
              cursor: selected ? (drag.dragging ? 'grabbing' : 'grab') : 'pointer',
              userSelect: 'none',
              touchAction: 'none',
              flexShrink: 0,
            }}
          >
            {cell.rest ? '·' : cell.syllable || ''}
          </span>
        );
      })}
      {active && (
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            width: CELL_WIDTH * s,
            height: CELL_HEIGHT * s,
            borderRight: '1px solid rgba(74, 74, 106, 0.2)',
            borderBottom: '1px solid rgba(74, 74, 106, 0.18)',
            background: 'rgba(99, 102, 241, 0.04)',
            flexShrink: 0,
          }}
        />
      )}
      <span
        style={{
          fontSize: 10 * s,
          color: active ? '#a0a0ff' : '#7a7a9a',
          fontFamily: 'monospace',
          fontWeight: active ? 'bold' : 'normal',
          marginLeft: 'auto',
          paddingLeft: 8 * s,
          paddingRight: 4 * s,
          flexShrink: 0,
          minWidth: 20 * s,
          textAlign: 'right',
        }}
      >
        {count || ''}
      </span>
      <span
        onClick={stop}
        onDoubleClick={stop}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 2 * s, width: ROW_CONTROLS_WIDTH * s, flexShrink: 0 }}
      >
        <button
          type="button"
          title="Insert a rest before the selection (Insert)"
          disabled={selection === null}
          onClick={props.onInsertRest}
          style={buttonStyle(s, selection !== null, selection !== null)}
        >
          +·
        </button>
        <button
          type="button"
          title="Remove the selected rests (Delete)"
          disabled={!selectedAllRests}
          onClick={props.onRemoveRests}
          style={buttonStyle(s, selectedAllRests, selection !== null)}
        >
          −
        </button>
        <button
          type="button"
          title="Move line up (Alt+Up)"
          disabled={!canMoveUp}
          onClick={() => props.onMoveRow(-1)}
          style={buttonStyle(s, canMoveUp)}
        >
          ▲
        </button>
        <button
          type="button"
          title="Move line down (Alt+Down)"
          disabled={!canMoveDown}
          onClick={() => props.onMoveRow(1)}
          style={buttonStyle(s, canMoveDown)}
        >
          ▼
        </button>
      </span>
      {drag.dropGap !== null && (
        <div
          style={{
            position: 'absolute',
            left: drag.dropGap * CELL_WIDTH * s - 1,
            top: 0,
            bottom: 0,
            width: 2,
            background: '#a0a0ff',
            pointerEvents: 'none',
          }}
        />
      )}
    </div>
  );
}
