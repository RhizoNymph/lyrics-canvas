import type {
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent,
} from 'react';
import { SNIPPET_TYPE_COLORS } from '@/types';
import { displayText } from '@/lib/grid/restMarks';
import type { Snippet } from '@/types';
import InlineGridEditor from '@/components/canvas/InlineGridEditor';
import CanvasBadge from '@/components/placements/CanvasBadge';
import GridPreview from './GridPreview';

interface Props {
  snippet: Snippet;
  selected: boolean;
  editing: boolean;
  onClick: (e: ReactMouseEvent) => void;
  onContextMenu: (e: ReactMouseEvent) => void;
  /** Keyboard open (Enter on a focused row). */
  onOpen: () => void;
  /** The "⧉ N" badge was clicked: list the snippet's canvases at this screen point. */
  onShowCanvases: (x: number, y: number) => void;
  /** Manual reorder (drag handle, Alt+Up / Alt+Down); null while reordering is off (an editor is open). */
  reorder: RowReorder | null;
}

export interface RowReorder {
  /** This row is being dragged. */
  dragging: boolean;
  onHandlePointerDown: (e: ReactPointerEvent<HTMLElement>) => void;
  /** Alt+Up (-1) / Alt+Down (+1) on the focused row. */
  onStep: (delta: -1 | 1) => void;
}

/** The drag handle at the left of the row header. Pointer only; the keyboard uses Alt+Up / Alt+Down. */
function DragHandle({ onPointerDown }: { onPointerDown: (e: ReactPointerEvent<HTMLElement>) => void }) {
  return (
    <span
      aria-hidden
      title="Drag to reorder (Alt+Up / Alt+Down)"
      data-drag-handle
      onPointerDown={onPointerDown}
      onClick={(e) => e.stopPropagation()}
      onContextMenu={(e) => e.stopPropagation()}
      style={{
        cursor: 'grab',
        touchAction: 'none',
        userSelect: 'none',
        fontSize: 14,
        lineHeight: 1,
        padding: '4px 4px 4px 0',
        opacity: 0.8,
      }}
    >
      ⠿
    </span>
  );
}

/** One snippet in list mode: the card's header (type colour, tags) and its beat grid, or the editor in place. */
export default function ListRow({ snippet, selected, editing, onClick, onContextMenu, onOpen, onShowCanvases, reorder }: Props) {
  const color = SNIPPET_TYPE_COLORS[snippet.snippet_type] || '#6b7280';
  const lines = snippet.grid_data?.lines ?? [];

  if (editing) {
    return (
      <div data-snippet-id={snippet.id} style={{ marginBottom: 12 }}>
        <InlineGridEditor key={snippet.id} snippetId={snippet.id} placement={{ kind: 'inline' }} />
      </div>
    );
  }

  const onKeyDown = (e: ReactKeyboardEvent) => {
    if (e.target !== e.currentTarget) return;
    if (e.key === 'Enter') {
      e.preventDefault();
      onOpen();
    } else if (reorder !== null && e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault();
      reorder.onStep(e.key === 'ArrowUp' ? -1 : 1);
    }
  };

  return (
    <div
      data-snippet-id={snippet.id}
      role="option"
      aria-selected={selected}
      aria-keyshortcuts={reorder !== null ? 'Alt+ArrowUp Alt+ArrowDown' : undefined}
      tabIndex={0}
      onClick={onClick}
      onContextMenu={onContextMenu}
      onKeyDown={onKeyDown}
      onMouseDown={(e) => {
        // Shift-click would otherwise select page text across rows.
        if (e.shiftKey) e.preventDefault();
      }}
      style={{
        marginBottom: 12,
        background: '#1c1c38',
        borderRadius: 8,
        overflow: 'hidden',
        cursor: 'pointer',
        outline: 'none',
        opacity: reorder?.dragging ? 0.45 : 1,
        boxShadow: selected ? '0 0 0 2.5px rgba(255,255,255,0.9)' : '0 2px 6px rgba(0,0,0,0.25)',
      }}
    >
      <div
        style={{
          height: 30,
          background: color,
          opacity: 0.92,
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          padding: '0 14px',
          color: '#fff',
        }}
      >
        {reorder !== null && <DragHandle onPointerDown={reorder.onHandlePointerDown} />}
        <span style={{ fontSize: 11, fontWeight: 'bold', letterSpacing: 0.3 }}>{snippet.snippet_type.toUpperCase()}</span>
        <CanvasBadge snippet={snippet} onOpen={onShowCanvases} />
        <span style={{ flex: 1 }} />
        {snippet.tags.map((tag) => (
          <span
            key={tag.id}
            style={{
              fontSize: 9,
              fontWeight: 'bold',
              padding: '2px 6px',
              borderRadius: 8,
              background: tag.color,
              opacity: 0.85,
              whiteSpace: 'nowrap',
            }}
          >
            {tag.name}
          </span>
        ))}
      </div>
      <div style={{ padding: 14 }}>
        {lines.length > 0 ? (
          <GridPreview lines={lines} />
        ) : (
          <div style={{ fontFamily: 'monospace', fontSize: 13, color: '#a0a0c0', whiteSpace: 'pre-wrap' }}>
            {displayText(snippet.raw_text).trim() || <span style={{ color: '#4a4a6a' }}>Empty snippet, click to write</span>}
          </div>
        )}
      </div>
    </div>
  );
}
