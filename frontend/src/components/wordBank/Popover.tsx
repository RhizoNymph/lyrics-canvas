import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { COLORS, popoverStyle, smallBtnStyle } from '@/components/tags/tagStyles';

interface Props {
  label: string;
  disabled?: boolean;
  title?: string;
  width?: number;
  children: ReactNode;
}

/** A button that opens a popover below it; outside click or Esc closes it. */
export default function Popover({ label, disabled = false, title, width = 280, children }: Props) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={rootRef} style={{ position: 'relative' }}>
      <button
        type="button"
        disabled={disabled}
        aria-expanded={open}
        title={title}
        onClick={() => setOpen((o) => !o)}
        style={{ ...smallBtnStyle, opacity: disabled ? 0.5 : 1, ...(open ? { borderColor: COLORS.accent } : {}) }}
      >
        {label}
      </button>
      {open && !disabled && <div style={{ ...popoverStyle, top: '100%', marginTop: 4, left: 0, width, padding: '4px 0' }}>{children}</div>}
    </div>
  );
}
