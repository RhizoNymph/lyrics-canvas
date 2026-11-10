import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import type { CanvasRef } from '@/types';

/** The "⧉ N" badge popover of the canvas and list views: every canvas holding the snippet; others navigate. */
export default function CanvasesPopover() {
  const popover = useUIStore((s) => s.canvasesPopover);
  const setCanvasesPopover = useUIStore((s) => s.setCanvasesPopover);
  const canvasId = useCanvasStore((s) => s.canvasId);
  const snippet = useCanvasStore((s) => (popover ? s.snippets.get(popover.snippetId) : undefined));
  const navigate = useNavigate();
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!popover) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setCanvasesPopover(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setCanvasesPopover(null);
    };
    // Deferred so the click that opened the popover does not close it.
    const id = setTimeout(() => document.addEventListener('mousedown', onDown), 50);
    document.addEventListener('keydown', onKey);
    return () => {
      clearTimeout(id);
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [popover, setCanvasesPopover]);

  if (!popover || !snippet || canvasId === null) return null;
  return (
    <div ref={ref}>
      <CanvasesPopoverView
        canvases={snippet.canvases}
        currentCanvasId={canvasId}
        x={popover.x}
        y={popover.y}
        onOpen={(id) => {
          setCanvasesPopover(null);
          navigate(`/canvas/${id}`);
        }}
      />
    </div>
  );
}

export function CanvasesPopoverView({
  canvases,
  currentCanvasId,
  x,
  y,
  onOpen,
}: {
  canvases: readonly CanvasRef[];
  currentCanvasId: string;
  x: number;
  y: number;
  onOpen: (canvasId: string) => void;
}) {
  return (
    <div
      role="dialog"
      aria-label="Canvases showing this snippet"
      data-keep-editor-open
      style={{
        position: 'fixed',
        left: x,
        top: y,
        zIndex: 2100,
        background: '#1e1e3a',
        border: '1px solid #3a3a5c',
        borderRadius: 8,
        padding: '4px 0',
        minWidth: 200,
        maxWidth: 320,
        boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        fontFamily: 'system-ui, sans-serif',
        fontSize: 13,
        color: '#d0d0e8',
      }}
    >
      <div style={{ padding: '4px 12px', fontSize: 11, color: '#8a8aaa' }}>On {canvases.length} canvases</div>
      {canvases.map((c) =>
        c.id === currentCanvasId ? (
          <div key={c.id} style={{ padding: '5px 12px', color: '#8a8aaa' }} aria-current="page">
            {c.title} <span style={{ fontSize: 11 }}>(this canvas)</span>
          </div>
        ) : (
          <button
            key={c.id}
            type="button"
            onClick={() => onOpen(c.id)}
            style={{
              display: 'block',
              width: '100%',
              textAlign: 'left',
              background: 'transparent',
              border: 'none',
              color: '#c7d2fe',
              padding: '5px 12px',
              cursor: 'pointer',
              fontSize: 13,
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
          >
            {c.title}
          </button>
        ),
      )}
    </div>
  );
}
