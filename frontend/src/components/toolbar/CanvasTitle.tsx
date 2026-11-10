import { useState } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import TitleEditor from '@/components/placements/TitleEditor';

/** The canvas header title: click to rename in place (Enter saves, Escape cancels). */
export default function CanvasTitle() {
  const canvasId = useCanvasStore((s) => s.canvasId);
  const canvasTitle = useCanvasStore((s) => s.canvasTitle);
  const renameCanvas = useCanvasStore((s) => s.renameCanvas);
  const [editing, setEditing] = useState(false);

  if (editing && canvasId !== null) {
    return (
      <span style={{ marginRight: 16 }}>
        <TitleEditor initial={canvasTitle} save={renameCanvas} onDone={() => setEditing(false)} onCancel={() => setEditing(false)} />
      </span>
    );
  }

  return (
    <button
      type="button"
      onClick={() => canvasId !== null && setEditing(true)}
      title="Rename canvas"
      style={{
        fontWeight: 'bold',
        fontSize: 15,
        marginRight: 16,
        color: '#e0e0e0',
        background: 'none',
        border: '1px solid transparent',
        borderRadius: 4,
        padding: '2px 6px',
        cursor: canvasId !== null ? 'text' : 'default',
        maxWidth: 320,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
      }}
      onMouseEnter={(e) => (e.currentTarget.style.borderColor = '#3a3a5a')}
      onMouseLeave={(e) => (e.currentTarget.style.borderColor = 'transparent')}
    >
      {canvasTitle || 'Lyrics Canvas'}
    </button>
  );
}
