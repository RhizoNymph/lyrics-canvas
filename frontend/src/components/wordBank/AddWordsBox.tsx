import type { ReactNode } from 'react';
import type { SnippetTag } from '@/types';
import { errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import { tagDotPillStyle } from './styles';

interface Props {
  text: string;
  /** The first entry that would be rejected (live), or a server error after a submit. */
  problem: string | null;
  busy: boolean;
  /** Tags linked to every added word. */
  chips: readonly SnippetTag[];
  onText: (text: string) => void;
  onSubmit: () => void;
  onRemoveChip: (tagId: string) => void;
  /** The "+ tag" control (a popover over the tag checklist). */
  tagControl: ReactNode;
}

/**
 * The add box: commas and newlines separate entries (spaces do not, so phrases are fine). Enter
 * submits, Shift+Enter starts a new line. The chips are the tags the words get.
 */
export default function AddWordsBox({ text, problem, busy, chips, onText, onSubmit, onRemoveChip, tagControl }: Props) {
  const blocked = busy || problem !== null || text.trim() === '';
  return (
    <div>
      <div style={{ display: 'flex', gap: 6, alignItems: 'flex-start' }}>
        <textarea
          rows={2}
          value={text}
          aria-label="Add words"
          aria-invalid={problem !== null}
          placeholder="Add words: night, neon lights, …"
          onChange={(e) => onText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              if (!blocked) onSubmit();
            }
          }}
          style={{ ...fieldStyle, resize: 'vertical', fontFamily: 'inherit', borderColor: problem !== null ? '#f87171' : undefined }}
        />
        <button type="button" disabled={blocked} onClick={onSubmit} style={{ ...smallBtnStyle, opacity: blocked ? 0.5 : 1 }}>
          {busy ? 'Adding…' : 'Add'}
        </button>
      </div>
      {problem !== null && <div style={{ ...errorTextStyle, marginTop: 2 }}>{problem}</div>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexWrap: 'wrap', marginTop: 4 }}>
        <span style={mutedTextStyle}>Tag new words:</span>
        {chips.map((t) => (
          <span key={t.id} style={{ ...tagDotPillStyle(t.color), gap: 2 }}>
            {t.name}
            <button
              type="button"
              aria-label={`Do not tag with ${t.name}`}
              onClick={() => onRemoveChip(t.id)}
              style={{ background: 'none', border: 'none', color: '#aaa', cursor: 'pointer', padding: 0, fontSize: 10 }}
            >
              ×
            </button>
          </span>
        ))}
        {tagControl}
      </div>
    </div>
  );
}
