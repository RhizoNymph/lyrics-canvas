import type { SlotColumn, SlotView, TuplePicks } from '@/lib/rhyme/pattern';
import { canLoadMoreSlot } from '@/lib/rhyme/pattern';
import { slotColor } from '../rhymeColors';
import { btnStyle, monoStyle, mutedStyle } from '../sidebarStyles';
import PatternCandidateRow from './PatternCandidateRow';
import type { PatternCandidate } from '@/types/rhymePattern';

/** Minimum column width; columns sit side by side and scroll horizontally when they do not fit. */
export const SLOT_COLUMN_MIN_WIDTH = 160;
export const SLOT_COLUMN_HEIGHT = 300;

interface Props {
  /** The on slots, in slot order. */
  slots: readonly SlotView[];
  columns: ReadonlyMap<string, SlotColumn> | null;
  picks: TuplePicks;
  paging: ReadonlySet<string>;
  loading: boolean;
  onPick: (slotId: string, candidate: PatternCandidate) => void;
  onDismiss: (id: string) => void;
  onMore: (slotId: string) => void;
}

/** One independently scrollable list per on slot (no Cartesian product). */
export default function SlotColumns({ slots, columns, picks, paging, loading, onPick, onDismiss, onMore }: Props) {
  return (
    <div style={{ display: 'flex', gap: 6, overflowX: 'auto', paddingBottom: 4 }}>
      {slots.map((s) => {
        const column = columns?.get(s.id);
        const colour = slotColor(s.index);
        const more = column !== undefined && canLoadMoreSlot(column) && !paging.has(s.id) && !loading;
        return (
          <div key={s.id} data-column={s.letter} style={{ flex: `1 0 ${SLOT_COLUMN_MIN_WIDTH}px`, minWidth: SLOT_COLUMN_MIN_WIDTH, display: 'flex', flexDirection: 'column', border: '1px solid #2a2a4a', borderTop: `3px solid ${colour}`, borderRadius: 4 }}>
            <div style={{ padding: '4px 6px', display: 'flex', gap: 6, alignItems: 'baseline', borderBottom: '1px solid #2a2a4a' }}>
              <b style={{ color: colour }}>{s.letter}</b>
              <span style={{ ...monoStyle, fontSize: 10, color: '#c0c0d8', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{s.key ?? ''}</span>
              {column !== undefined && <span style={mutedStyle}>{column.total}</span>}
            </div>
            <div style={{ height: SLOT_COLUMN_HEIGHT, overflowY: 'auto' }}>
              {column === undefined ? (
                <div style={{ ...mutedStyle, padding: 6 }}>{loading ? 'Searching…' : '—'}</div>
              ) : column.candidates.length === 0 ? (
                <div style={{ ...mutedStyle, padding: 6 }}>Nothing rhymes with every member at this score.</div>
              ) : (
                column.candidates.map((c) => (
                  <PatternCandidateRow
                    key={c.id}
                    candidate={c}
                    colour={colour}
                    picked={picks.get(s.id)?.candidateId === c.id}
                    onPick={() => onPick(s.id, c)}
                    onDismiss={() => onDismiss(c.id)}
                  />
                ))
              )}
              {column !== undefined && canLoadMoreSlot(column) && (
                <div style={{ padding: 6, textAlign: 'center' }}>
                  <button type="button" onClick={() => onMore(s.id)} disabled={!more} style={{ ...btnStyle, fontSize: 11, opacity: more ? 1 : 0.5 }}>
                    {paging.has(s.id) ? 'Loading…' : 'More'}
                  </button>
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
