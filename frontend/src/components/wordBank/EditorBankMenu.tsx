import { useEffect, useRef } from 'react';
import { ADD_SELECTION_SHORTCUT_LABEL } from '@/lib/wordBank';
import { COLORS, popoverStyle } from '@/components/tags/tagStyles';

interface Props {
  x: number;
  y: number;
  onAdd: () => void;
  onClose: () => void;
}

/**
 * The inline editor's context menu on a text selection: "Add to word bank". Marked
 * data-keep-editor-open so using it does not save and close the editor; outside click or Esc closes it.
 */
export default function EditorBankMenu({ x, y, onAdd, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey, true);
    };
  }, [onClose]);

  return (
    <div ref={ref} role="menu" data-keep-editor-open style={{ ...popoverStyle, position: 'fixed', left: x, top: y, padding: '4px 0', minWidth: 200 }}>
      <button
        type="button"
        role="menuitem"
        autoFocus
        // Keep the input's selection while clicking.
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => {
          onAdd();
          onClose();
        }}
        style={{
          display: 'flex', width: '100%', justifyContent: 'space-between', gap: 12, background: 'none', border: 'none',
          color: COLORS.text, padding: '6px 12px', cursor: 'pointer', fontSize: 12, textAlign: 'left',
        }}
      >
        <span>Add to word bank</span>
        <span style={{ color: COLORS.muted }}>{ADD_SELECTION_SHORTCUT_LABEL}</span>
      </button>
    </div>
  );
}
