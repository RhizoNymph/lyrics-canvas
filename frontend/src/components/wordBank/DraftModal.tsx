import { useRef, useState } from 'react';
import { describeWordBankError } from '@/lib/wordBank';
import type { SnippetDraft } from '@/lib/wordBank';
import { useWordBankStore } from '@/stores/wordBankStore';
import { useFocusTrap } from '@/components/voice/useFocusTrap';
import DraftView from './DraftView';

interface Props {
  initial: SnippetDraft;
  onClose: () => void;
}

/** The draft editor modal: Create posts the snippet at the viewport centre and closes; Cancel discards. */
export default function DraftModal({ initial, onClose }: Props) {
  const makeSnippet = useWordBankStore((s) => s.makeSnippet);
  const [text, setText] = useState(initial.text);
  const [type, setType] = useState(initial.type);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);

  const create = async () => {
    setBusy(true);
    setError(null);
    const result = await makeSnippet({ text, type });
    setBusy(false);
    if (result.ok) onClose();
    else setError(describeWordBankError(result.error));
  };

  return (
    <DraftView
      text={text}
      type={type}
      busy={busy}
      error={error}
      surfaceRef={surfaceRef}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !busy) {
          e.preventDefault();
          e.stopPropagation();
          onClose();
          return;
        }
        onTrapKeyDown(e);
      }}
      onText={setText}
      onType={setType}
      onCreate={() => void create()}
      onCancel={onClose}
    />
  );
}
