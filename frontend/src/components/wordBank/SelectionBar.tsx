import type { ReactNode } from 'react';
import { COLORS, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';

export type DeleteStep = { kind: 'idle' } | { kind: 'confirm'; count: number } | { kind: 'busy' };

interface Props {
  count: number;
  /** The "Tags" control (a popover over the tag checklist for the selection). */
  tagControl: ReactNode;
  deleteStep: DeleteStep;
  onMakeSnippet: () => void;
  onDelete: () => void;
  onCancelDelete: () => void;
  onClear: () => void;
}

/** Actions on the selected words: tags, make a snippet, delete (confirmed above 5), clear. */
export default function SelectionBar({ count, tagControl, deleteStep, onMakeSnippet, onDelete, onCancelDelete, onClear }: Props) {
  const none = count === 0;
  if (deleteStep.kind === 'confirm') {
    return (
      <div role="alertdialog" aria-label="Confirm delete" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ color: COLORS.danger, fontSize: 12 }}>Delete {deleteStep.count} words? This cannot be undone.</span>
        <button type="button" onClick={onDelete} style={{ ...smallBtnStyle, color: COLORS.danger }}>
          Delete
        </button>
        <button type="button" onClick={onCancelDelete} style={smallBtnStyle}>
          Cancel
        </button>
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      <span style={mutedTextStyle} aria-live="polite">
        {none ? 'Nothing selected' : `${count} selected`}
      </span>
      {tagControl}
      <button
        type="button"
        disabled={none}
        onClick={onMakeSnippet}
        style={{ ...smallBtnStyle, opacity: none ? 0.5 : 1 }}
        title="Open a draft with the selected words, one per line"
      >
        Make snippet
      </button>
      <button
        type="button"
        disabled={none || deleteStep.kind === 'busy'}
        onClick={onDelete}
        style={{ ...smallBtnStyle, opacity: none ? 0.5 : 1, color: none ? undefined : COLORS.danger }}
      >
        Delete
      </button>
      {!none && (
        <button type="button" onClick={onClear} style={smallBtnStyle}>
          Clear
        </button>
      )}
    </div>
  );
}
