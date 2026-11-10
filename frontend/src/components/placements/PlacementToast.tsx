import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import type { PlacementNotice } from '@/stores/placementNoticeStore';

export const PLACEMENT_NOTICE_MS = 6000;

/** The transfer / delete result line, bottom centre, auto-dismissed; "Open" navigates to the target canvas. */
export default function PlacementToast() {
  const notice = usePlacementNoticeStore((s) => s.notice);
  const dismiss = usePlacementNoticeStore((s) => s.dismiss);
  const navigate = useNavigate();

  useEffect(() => {
    if (notice === null) return;
    const timer = setTimeout(() => dismiss(notice.id), PLACEMENT_NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice, dismiss]);

  if (notice === null) return null;
  return (
    <PlacementToastView
      notice={notice}
      onDismiss={() => dismiss(notice.id)}
      onOpen={(id) => {
        dismiss(notice.id);
        navigate(`/canvas/${id}`);
      }}
    />
  );
}

export function PlacementToastView({
  notice,
  onDismiss,
  onOpen,
}: {
  notice: PlacementNotice;
  onDismiss: () => void;
  onOpen: (canvasId: string) => void;
}) {
  const error = notice.tone === 'error';
  return (
    <div
      role={error ? 'alert' : 'status'}
      data-keep-editor-open
      style={{
        position: 'fixed',
        bottom: 64,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 2600,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        background: '#1e1e3a',
        border: `1px solid ${error ? '#f87171' : '#6366f1'}`,
        color: error ? '#fca5a5' : '#e0e0f0',
        borderRadius: 6,
        padding: '8px 14px',
        fontSize: 13,
        boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        maxWidth: '80vw',
      }}
    >
      <span>{notice.text}</span>
      {notice.open !== null && (
        <button
          type="button"
          onClick={() => notice.open && onOpen(notice.open.id)}
          title={`Open ${notice.open.title}`}
          style={{ background: 'none', border: 'none', color: '#a5b4fc', fontWeight: 'bold', cursor: 'pointer', fontSize: 13, padding: 0 }}
        >
          Open
        </button>
      )}
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss"
        style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 14, padding: 0 }}
      >
        ×
      </button>
    </div>
  );
}
