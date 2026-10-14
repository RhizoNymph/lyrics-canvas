import type { KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent } from 'react';
import { SNIPPET_TYPE_COLORS } from '@/types';
import type { Snippet } from '@/types';
import InlineGridEditor from '@/components/canvas/InlineGridEditor';
import GridPreview from './GridPreview';

interface Props {
  snippet: Snippet;
  selected: boolean;
  editing: boolean;
  onClick: (e: ReactMouseEvent) => void;
  onContextMenu: (e: ReactMouseEvent) => void;
  /** Keyboard open (Enter on a focused row). */
  onOpen: () => void;
}

/** One snippet in list mode: the card's header (type colour, tags) and its beat grid, or the editor in place. */
export default function ListRow({ snippet, selected, editing, onClick, onContextMenu, onOpen }: Props) {
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
    if (e.key === 'Enter' && e.target === e.currentTarget) {
      e.preventDefault();
      onOpen();
    }
  };

  return (
    <div
      data-snippet-id={snippet.id}
      role="option"
      aria-selected={selected}
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
        <span style={{ fontSize: 11, fontWeight: 'bold', letterSpacing: 0.3 }}>{snippet.snippet_type.toUpperCase()}</span>
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
            {snippet.raw_text.trim() || <span style={{ color: '#4a4a6a' }}>Empty snippet, click to write</span>}
          </div>
        )}
      </div>
    </div>
  );
}
