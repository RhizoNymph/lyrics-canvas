import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { submitCanvasTitle, titleEditKey } from '@/lib/placements';
import type { PlacementResult } from '@/lib/placements';

interface Props {
  initial: string;
  /** Saves the trimmed, locally valid title. */
  save: (title: string) => Promise<PlacementResult<unknown>>;
  /** Called after a successful save. */
  onDone: () => void;
  onCancel: () => void;
  inputStyle?: CSSProperties;
  ariaLabel?: string;
}

/**
 * Inline canvas title editor (canvas list card and canvas header): Enter saves, Escape cancels; the local
 * check (trim, 1..255) and the server's `invalid_canvas_title` show inline under the input. Blurring an
 * unchanged title cancels.
 */
export default function TitleEditor({ initial, save, onDone, onCancel, inputStyle, ariaLabel = 'Canvas title' }: Props) {
  const [value, setValue] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  const submit = async () => {
    if (saving) return;
    setSaving(true);
    const message = await submitCanvasTitle(value, save);
    setSaving(false);
    if (message === null) onDone();
    else setError(message);
  };

  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', gap: 2 }} onClick={(e) => e.stopPropagation()}>
      <input
        ref={inputRef}
        value={value}
        aria-label={ariaLabel}
        aria-invalid={error !== null}
        disabled={saving}
        onChange={(e) => {
          setValue(e.target.value);
          setError(null);
        }}
        onKeyDown={(e) => {
          const action = titleEditKey(e.key);
          if (action === null) return;
          e.preventDefault();
          e.stopPropagation();
          if (action === 'save') void submit();
          else onCancel();
        }}
        onBlur={() => {
          if (!saving && value === initial) onCancel();
        }}
        style={{
          background: '#1e1e3a',
          color: '#e0e0e0',
          border: `1px solid ${error ? '#f87171' : '#6366f1'}`,
          borderRadius: 4,
          padding: '4px 8px',
          fontSize: 14,
          fontWeight: 'bold',
          outline: 'none',
          minWidth: 220,
          ...inputStyle,
        }}
      />
      {error !== null && (
        <span role="alert" style={{ color: '#fca5a5', fontSize: 11, fontWeight: 'normal' }}>
          {error}
        </span>
      )}
    </span>
  );
}
