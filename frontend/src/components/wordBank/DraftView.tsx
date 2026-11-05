import type { KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { SNIPPET_TYPES } from '@/lib/tagExpr';
import { isDraftCreatable } from '@/lib/wordBank';
import type { SnippetType } from '@/types';
import { COLORS, errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';

interface Props {
  text: string;
  type: SnippetType;
  busy: boolean;
  error: string | null;
  surfaceRef?: RefObject<HTMLDivElement>;
  onKeyDown?: (e: ReactKeyboardEvent) => void;
  onText: (text: string) => void;
  onType: (type: SnippetType) => void;
  onCreate: () => void;
  onCancel: () => void;
}

/** The "Make snippet" draft: editable text (one word per line to start), a type, Create / Cancel. */
export default function DraftView({ text, type, busy, error, surfaceRef, onKeyDown, onText, onType, onCreate, onCancel }: Props) {
  const creatable = isDraftCreatable(text) && !busy;
  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 2500, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel();
      }}
    >
      <div
        ref={surfaceRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="word-bank-draft-title"
        tabIndex={-1}
        onKeyDown={onKeyDown}
        style={{ background: COLORS.panel, border: `1px solid ${COLORS.border}`, borderRadius: 8, padding: 16, width: 420, maxWidth: '90vw', color: COLORS.text, outline: 'none' }}
      >
        <h2 id="word-bank-draft-title" style={{ margin: '0 0 8px', fontSize: 15 }}>New snippet from words</h2>
        <div style={{ ...mutedTextStyle, marginBottom: 6 }}>Edit freely; it is created at the centre of the canvas view. The words stay in the bank.</div>
        <textarea
          autoFocus
          aria-label="Draft text"
          value={text}
          rows={10}
          onChange={(e) => onText(e.target.value)}
          style={{ ...fieldStyle, fontFamily: 'monospace', fontSize: 13, resize: 'vertical' }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
          <label style={mutedTextStyle}>
            Type{' '}
            <select aria-label="Snippet type" value={type} onChange={(e) => onType(e.target.value as SnippetType)} style={{ ...fieldStyle, width: 'auto' }}>
              {SNIPPET_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <span style={{ flex: 1 }} />
          <button type="button" onClick={onCancel} disabled={busy} style={smallBtnStyle}>
            Cancel
          </button>
          <button
            type="button"
            onClick={onCreate}
            disabled={!creatable}
            style={{ ...smallBtnStyle, background: COLORS.accent, color: '#fff', borderColor: COLORS.accent, opacity: creatable ? 1 : 0.5 }}
          >
            {busy ? 'Creating…' : 'Create'}
          </button>
        </div>
        {error !== null && <div style={{ ...errorTextStyle, marginTop: 6 }}>{error}</div>}
      </div>
    </div>
  );
}
