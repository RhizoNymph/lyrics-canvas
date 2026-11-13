import type { LineMatch } from '@/types/rhymePattern';
import { lineMatchWords, slotsHitLabel } from '@/lib/rhyme/pattern';
import { slotColor } from '../rhymeColors';
import { btnStyle, monoStyle, mutedStyle, primaryBtnStyle } from '../sidebarStyles';

interface Props {
  matches: readonly LineMatch[];
  total: number;
  /** On slots searched. */
  slotCount: number;
  /** Slot order index for a slot id, or null when it is not shown. */
  slotIndex: (slotId: string) => number | null;
  canMore: boolean;
  loadingMore: boolean;
  onMaterialize: (unitId: string) => void;
  onDismiss: (unitId: string) => void;
  onMore: () => void;
}

/** "Whole-line matches": the user's own lines that already hit 2+ slots in order, hits in slot colours. */
export default function LineMatchList({ matches, total, slotCount, slotIndex, canMore, loadingMore, onMaterialize, onDismiss, onMore }: Props) {
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
        <span style={{ fontSize: 12, fontWeight: 'bold' }}>Whole-line matches</span>
        <span style={mutedStyle}>{total}</span>
      </div>
      {matches.length === 0 && <div style={mutedStyle}>None of your lines hit 2 or more of these slots.</div>}
      {matches.map((m) => (
        <div key={m.unit.id} data-unit-id={m.unit.id} style={{ padding: '5px 0', borderBottom: '1px solid #22223f' }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3, fontSize: 12 }}>
            {lineMatchWords(m, slotIndex).map((w, i) => (
              <span key={i} style={w.slotIndex === null ? { color: '#d0d0e8' } : { color: slotColor(w.slotIndex), fontWeight: 'bold' }}>
                {w.text}
              </span>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 3 }}>
            <span style={mutedStyle}>{slotsHitLabel(m, slotCount)}</span>
            <span style={{ ...monoStyle, fontSize: 10, color: '#c0c0d8' }}>{m.score.toFixed(2)}</span>
            <span style={{ flex: 1 }} />
            <button type="button" onClick={() => onMaterialize(m.unit.id)} style={{ ...primaryBtnStyle, padding: '1px 6px', fontSize: 11 }} title="Create a snippet from this line on the canvas">
              Materialize
            </button>
            <button type="button" onClick={() => onDismiss(m.unit.id)} aria-label="Dismiss" title="Hide for this session" style={{ ...btnStyle, padding: '1px 6px', fontSize: 11 }}>
              ×
            </button>
          </div>
        </div>
      ))}
      {canMore && (
        <div style={{ textAlign: 'center', marginTop: 4 }}>
          <button type="button" onClick={onMore} disabled={loadingMore} style={{ ...btnStyle, fontSize: 11 }}>
            {loadingMore ? 'Loading…' : 'More'}
          </button>
        </div>
      )}
    </div>
  );
}
