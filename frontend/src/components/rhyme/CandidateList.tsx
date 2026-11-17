import { useState } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import type { RhymeState } from '@/stores/rhymeStore';
import { listRows, shownRange, canLoadMore } from '@/lib/rhyme/candidateList';
import CandidateRow from './CandidateRow';
import { presentationOf, withBandHeaders } from '@/lib/rhyme/order';
import { RHYTHM_OFF, listPresentation } from '@/lib/rhyme/rhythmFit';
import { BandHeader, OrderNote } from './order/orderParts';
import { RhythmNote } from './rhythm/rhythmParts';
import { btnStyle, mutedStyle, errorStyle } from './sidebarStyles';

/** The store state and actions the list reads (the store itself in the app; plain values in tests). */
export type CandidateListProps = Pick<
  RhymeState,
  | 'candidates' | 'anchors' | 'dismissed' | 'offset' | 'total' | 'slot' | 'loading' | 'error' | 'ordering'
  | 'loadMore' | 'dismiss' | 'materialize' | 'setTypedAnchor'
> &
  /** Rhythm fit of the loaded results; omitted = off. */
  Partial<Pick<RhymeState, 'rhythm'>>;

/**
 * The scrollable result list with the status line, errors, and the More button. In meaning order a band
 * header (Perfect / Strong / Near / Loose) starts each band; a meaning request answered in rhyme order shows
 * a quiet "Meaning order unavailable" instead. Rhythm fit, when applied, bands the rows in either order (the
 * server bands them), and a requested but unavailable rhythm fit shows its reason.
 */
export function CandidateListView(props: CandidateListProps) {
  const { candidates, anchors, dismissed, offset, total, slot, loading, error, ordering, loadMore, dismiss, materialize, setTypedAnchor } = props;
  const rhythm = props.rhythm ?? RHYTHM_OFF;
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const rows = listRows(candidates, offset, dismissed);
  const range = shownRange(rows.length, total);
  const more = canLoadMore(loading, offset, total);
  const presentation = listPresentation(ordering, rhythm);
  const orderNote = presentationOf(ordering);

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
          {error.kind === 'invalid_tag_filter'
            ? `Not searched: ${error.message}`
            : `${error.operation === 'materialize' ? 'Materialize failed' : 'Query failed'} (${error.kind}): ${error.message}`}
        </div>
      )}

      {!loading && rows.length > 0 && orderNote === 'unavailable' && (
        <div style={{ padding: '4px 12px 0' }}>
          <OrderNote presentation={orderNote} />
        </div>
      )}

      {!loading && rhythm.unavailable !== null && (
        <div style={{ padding: '4px 12px 0' }}>
          <RhythmNote rhythm={rhythm} />
        </div>
      )}

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px 12px' }}>
        {withBandHeaders(rows, presentation).map((item) => {
          if (item.kind === 'header') return <BandHeader key={item.key} band={item.band} />;
          const c = item.row;
          return (
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
          );
        })}
      </div>
    </div>
  );
}

export default function CandidateList() {
  const candidates = useRhymeStore((s) => s.candidates);
  const anchors = useRhymeStore((s) => s.anchors);
  const dismissed = useRhymeStore((s) => s.dismissed);
  const offset = useRhymeStore((s) => s.offset);
  const total = useRhymeStore((s) => s.total);
  const slot = useRhymeStore((s) => s.slot);
  const loading = useRhymeStore((s) => s.loading);
  const error = useRhymeStore((s) => s.error);
  const ordering = useRhymeStore((s) => s.ordering);
  const rhythm = useRhymeStore((s) => s.rhythm);
  const loadMore = useRhymeStore((s) => s.loadMore);
  const dismiss = useRhymeStore((s) => s.dismiss);
  const materialize = useRhymeStore((s) => s.materialize);
  const setTypedAnchor = useRhymeStore((s) => s.setTypedAnchor);
  return (
    <CandidateListView
      candidates={candidates}
      anchors={anchors}
      dismissed={dismissed}
      offset={offset}
      total={total}
      slot={slot}
      loading={loading}
      error={error}
      ordering={ordering}
      rhythm={rhythm}
      loadMore={loadMore}
      dismiss={dismiss}
      materialize={materialize}
      setTypedAnchor={setTypedAnchor}
    />
  );
}
