import { useState } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { listRows, shownRange, canLoadMore } from '@/lib/rhyme/candidateList';
import CandidateRow from './CandidateRow';
import { btnStyle, mutedStyle, errorStyle } from './sidebarStyles';

/** The scrollable result list with the status line, errors, and the More button. */
export default function CandidateList() {
  const candidates = useRhymeStore((s) => s.candidates);
  const anchors = useRhymeStore((s) => s.anchors);
  const dismissed = useRhymeStore((s) => s.dismissed);
  const offset = useRhymeStore((s) => s.offset);
  const total = useRhymeStore((s) => s.total);
  const slot = useRhymeStore((s) => s.slot);
  const loading = useRhymeStore((s) => s.loading);
  const error = useRhymeStore((s) => s.error);
  const loadMore = useRhymeStore((s) => s.loadMore);
  const dismiss = useRhymeStore((s) => s.dismiss);
  const materialize = useRhymeStore((s) => s.materialize);
  const setTypedAnchor = useRhymeStore((s) => s.setTypedAnchor);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const rows = listRows(candidates, offset, dismissed);
  const range = shownRange(rows.length, total);
  const more = canLoadMore(loading, offset, total);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', borderTop: '1px solid #2a2a4a', borderBottom: '1px solid #2a2a4a' }}>
        <span style={mutedStyle}>
          {loading ? 'Searching…' : rows.length === 0 ? 'No candidates' : `Showing ${range.start}–${range.end} of ${total}`}
        </span>
        <button onClick={() => void loadMore()} disabled={!more} style={{ ...btnStyle, opacity: more ? 1 : 0.5 }}>
          More
        </button>
      </div>

      {error && (
        <div style={{ ...errorStyle, padding: '6px 12px' }}>
          {error.operation === 'materialize' ? 'Materialize failed' : 'Query failed'} ({error.kind}): {error.message}
        </div>
      )}

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px 12px' }}>
        {rows.map((c) => (
          <CandidateRow
            key={c.id}
            candidate={c}
            anchor={anchors.find((a) => a.id === c.best_anchor_id)}
            hasSlot={slot !== null}
            expanded={expandedId === c.id}
            onToggle={() => setExpandedId((cur) => (cur === c.id ? null : c.id))}
            onChain={() => setTypedAnchor(c.text)}
            onMaterialize={() => void materialize(c.id)}
            onDismiss={() => dismiss(c.id)}
          />
        ))}
      </div>
    </div>
  );
}
