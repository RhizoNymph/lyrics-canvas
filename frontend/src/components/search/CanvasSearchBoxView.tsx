import type { KeyboardEvent as ReactKeyboardEvent, Ref } from 'react';
import { COLORS, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';

interface Props {
  text: string;
  /** "N of M" while a search applies, else null. */
  countLabel: string | null;
  onChange: (text: string) => void;
  onClear: () => void;
  inputRef?: Ref<HTMLInputElement>;
}

/** The in-canvas search box: live filter over display text, × and Escape clear, "N of M" count. */
export default function CanvasSearchBoxView({ text, countLabel, onChange, onClear, inputRef }: Props) {
  const onKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    onClear();
  };
  return (
    <div role="search" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: COLORS.text }}>
      <span style={{ ...mutedTextStyle, lineHeight: '24px' }}>Search</span>
      <input
        ref={inputRef}
        type="search"
        value={text}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Words in snippets (Ctrl+F)"
        aria-label="Search snippets on this canvas"
        spellCheck={false}
        style={{ ...fieldStyle, width: 200, height: 24 }}
      />
      <button
        type="button"
        onClick={onClear}
        disabled={text === ''}
        style={{ ...smallBtnStyle, lineHeight: '16px' }}
        title="Clear the search (Esc)"
        aria-label="Clear the search"
      >
        ✕
      </button>
      {countLabel !== null && (
        <span style={{ ...mutedTextStyle, lineHeight: '24px', whiteSpace: 'nowrap' }} aria-live="polite">
          {countLabel}
        </span>
      )}
    </div>
  );
}
