import { useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { checkWordText, describeWordProblem } from '@/lib/wordBank';
import type { BankWord } from '@/types/wordBank';
import { errorTextStyle, fieldStyle } from '@/components/tags/tagStyles';
import { rowStyle, tagDotPillStyle } from './styles';

interface Props {
  word: BankWord;
  selected: boolean;
  renaming: boolean;
  onClick: (e: ReactMouseEvent) => void;
  onCheckbox: () => void;
  onStartRename: () => void;
  /** Resolves an error message, or null when the rename went through. */
  onRename: (text: string) => Promise<string | null>;
  onCancelRename: () => void;
}

/** One bank word: checkbox, text (double-click or ✎ to rename inline), tag pills. */
export default function WordRow({ word, selected, renaming, onClick, onCheckbox, onStartRename, onRename, onCancelRename }: Props) {
  if (renaming) return <RenameRow word={word} onRename={onRename} onCancel={onCancelRename} />;
  return (
    <div
      role="option"
      aria-selected={selected}
      data-word-id={word.id}
      style={rowStyle(selected)}
      onClick={onClick}
      onDoubleClick={onStartRename}
    >
      <input
        type="checkbox"
        checked={selected}
        aria-label={`Select ${word.text}`}
        onClick={(e) => e.stopPropagation()}
        onChange={onCheckbox}
        style={{ margin: 0, flexShrink: 0 }}
      />
      <span style={{ fontSize: 13, color: '#e0e0f0', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{word.text}</span>
      <span style={{ display: 'flex', gap: 3, flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
        {word.tags.map((t) => (
          <span key={t.id} style={tagDotPillStyle(t.color)}>
            {t.name}
          </span>
        ))}
      </span>
      <button
        type="button"
        aria-label={`Rename ${word.text}`}
        title="Rename"
        onClick={(e) => {
          e.stopPropagation();
          onStartRename();
        }}
        style={{ background: 'none', border: 'none', color: '#6a6a8a', cursor: 'pointer', fontSize: 11, padding: '0 2px' }}
      >
        ✎
      </button>
    </div>
  );
}

function RenameRow({ word, onRename, onCancel }: { word: BankWord; onRename: (text: string) => Promise<string | null>; onCancel: () => void }) {
  const [text, setText] = useState(word.text);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const check = checkWordText(text);
  const shown = error ?? (!check.ok && text.trim() !== '' ? describeWordProblem(check.problem) : null);

  const submit = async () => {
    if (!check.ok) {
      setError(describeWordProblem(check.problem));
      return;
    }
    setBusy(true);
    const message = await onRename(text);
    setBusy(false);
    if (message !== null) setError(message);
  };

  return (
    <div style={{ padding: '4px 8px' }}>
      <input
        autoFocus
        aria-label={`New text for ${word.text}`}
        value={text}
        disabled={busy}
        onChange={(e) => {
          setText(e.target.value);
          setError(null);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') void submit();
          if (e.key === 'Escape') {
            e.stopPropagation();
            onCancel();
          }
        }}
        onBlur={() => {
          if (!busy && text === word.text) onCancel();
        }}
        style={{ ...fieldStyle, borderColor: check.ok ? undefined : '#f87171' }}
      />
      {shown !== null && <div style={{ ...errorTextStyle, marginTop: 2 }}>{shown}</div>}
    </div>
  );
}
