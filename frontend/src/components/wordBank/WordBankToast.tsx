import { useEffect } from 'react';
import { useWordBankStore } from '@/stores/wordBankStore';
import type { WordBankNotice } from '@/stores/wordBankStore';

export const NOTICE_MS = 4000;

/** The word bank's result line ("Added 2, 1 already in bank" or a failure), bottom centre, auto-dismissed. */
export default function WordBankToast() {
  const notice = useWordBankStore((s) => s.notice);
  const dismiss = useWordBankStore((s) => s.dismissNotice);

  useEffect(() => {
    if (notice === null) return;
    const timer = setTimeout(() => dismiss(notice.id), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice, dismiss]);

  return notice === null ? null : <WordBankToastView notice={notice} onDismiss={() => dismiss(notice.id)} />;
}

export function WordBankToastView({ notice, onDismiss }: { notice: WordBankNotice; onDismiss: () => void }) {
  return (
    <div
      role={notice.tone === 'error' ? 'alert' : 'status'}
      data-keep-editor-open
      onClick={onDismiss}
      title="Click to dismiss"
      style={{
        position: 'fixed',
        bottom: 24,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 2600,
        background: '#1e1e3a',
        border: `1px solid ${notice.tone === 'error' ? '#f87171' : '#6366f1'}`,
        color: notice.tone === 'error' ? '#fca5a5' : '#e0e0f0',
        borderRadius: 6,
        padding: '8px 14px',
        fontSize: 13,
        boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        cursor: 'pointer',
        maxWidth: '80vw',
      }}
    >
      {notice.text}
    </div>
  );
}
