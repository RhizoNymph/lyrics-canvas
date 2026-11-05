import type { MouseEvent as ReactMouseEvent } from 'react';
import type { BankWord } from '@/types/wordBank';
import { mutedTextStyle } from '@/components/tags/tagStyles';
import WordRow from './WordRow';

interface Props {
  words: readonly BankWord[];
  /** The bank is empty (as opposed to everything being filtered out). */
  bankEmpty: boolean;
  selected: ReadonlySet<string>;
  renamingId: string | null;
  onRowClick: (id: string, e: ReactMouseEvent) => void;
  onCheckbox: (id: string) => void;
  onStartRename: (id: string) => void;
  onRename: (id: string, text: string) => Promise<string | null>;
  onCancelRename: () => void;
}

/** The filtered words in list order (newest first) with multi-select. */
export default function WordList({ words, bankEmpty, selected, renamingId, onRowClick, onCheckbox, onStartRename, onRename, onCancelRename }: Props) {
  if (words.length === 0) {
    return (
      <div style={{ ...mutedTextStyle, padding: '8px 12px' }}>
        {bankEmpty ? 'No words yet. Type some above, or add them from rhymes, related words or a snippet selection.' : 'No word matches the filter.'}
      </div>
    );
  }
  return (
    <div role="listbox" aria-label="Words" aria-multiselectable style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      {words.map((w) => (
        <WordRow
          key={w.id}
          word={w}
          selected={selected.has(w.id)}
          renaming={renamingId === w.id}
          onClick={(e) => onRowClick(w.id, e)}
          onCheckbox={() => onCheckbox(w.id)}
          onStartRename={() => onStartRename(w.id)}
          onRename={(text) => onRename(w.id, text)}
          onCancelRename={onCancelRename}
        />
      ))}
    </div>
  );
}
