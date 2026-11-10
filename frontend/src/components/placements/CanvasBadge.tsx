import type { MouseEvent as ReactMouseEvent } from 'react';
import { canvasBadge } from '@/lib/placements';
import type { Snippet } from '@/types';

/** The DOM "⧉ N" link badge (list-mode row header); clicking opens the canvases popover under it. */
export default function CanvasBadge({ snippet, onOpen }: { snippet: Snippet; onOpen: (x: number, y: number) => void }) {
  const badge = canvasBadge(snippet);
  if (badge === null) return null;
  const onClick = (e: ReactMouseEvent<HTMLButtonElement>) => {
    e.stopPropagation();
    const rect = e.currentTarget.getBoundingClientRect();
    onOpen(rect.left, rect.bottom + 4);
  };
  return (
    <button
      type="button"
      onClick={onClick}
      onMouseDown={(e) => e.stopPropagation()}
      aria-label={`On ${badge.count} canvases: ${snippet.canvases.map((c) => c.title).join(', ')}`}
      title="Also on other canvases; click to list them"
      style={{
        fontSize: 10,
        fontWeight: 'bold',
        padding: '1px 6px',
        borderRadius: 8,
        border: '1px solid rgba(255,255,255,0.5)',
        background: 'rgba(0,0,0,0.25)',
        color: '#fff',
        cursor: 'pointer',
        whiteSpace: 'nowrap',
      }}
    >
      {badge.label}
    </button>
  );
}
