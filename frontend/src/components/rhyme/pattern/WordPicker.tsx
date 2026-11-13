import type { WordAction } from '@/lib/rhyme/pattern';
import { btnStyle, mutedStyle } from '../sidebarStyles';

interface Props {
  word: string;
  actions: readonly WordAction[];
  onAction: (action: WordAction) => void;
  onClose: () => void;
}

/** The small picker under a tapped word: add / extend / move / remove / new slot. Refused edits are disabled with the reason. */
export default function WordPicker({ word, actions, onAction, onClose }: Props) {
  return (
    <div role="menu" aria-label={`Slot actions for ${word}`} style={{ margin: '4px 0 4px 24px', padding: 6, background: '#22223f', border: '1px solid #3a3a5a', borderRadius: 4, display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center' }}>
      <span style={{ ...mutedStyle, marginRight: 4 }}>"{word}"</span>
      {actions.map((a) => (
        <span key={a.label} title={a.disabled ?? undefined}>
          <button
            type="button"
            role="menuitem"
            disabled={a.disabled !== null}
            onClick={() => onAction(a)}
            style={{ ...btnStyle, padding: '1px 6px', fontSize: 11, opacity: a.disabled === null ? 1 : 0.45, cursor: a.disabled === null ? 'pointer' : 'not-allowed' }}
          >
            {a.label}
          </button>
        </span>
      ))}
      <button type="button" onClick={onClose} aria-label="Close" style={{ ...btnStyle, padding: '1px 6px', fontSize: 11 }}>
        ×
      </button>
    </div>
  );
}
