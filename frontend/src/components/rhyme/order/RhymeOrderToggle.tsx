import { useRhymeOrderStore } from '@/stores/rhymeOrderStore';
import { RHYME_ORDERS, RHYME_ORDER_LABELS } from '@/lib/rhyme/order';
import type { RhymeOrder } from '@/lib/rhyme/order';
import { btnStyle, labelStyle, primaryBtnStyle } from '../sidebarStyles';

interface ViewProps {
  order: RhymeOrder;
  onChange: (order: RhymeOrder) => void;
  /** Visible label above the two buttons; omitted = none (the group keeps its accessible name). */
  label?: string;
}

/** "Rhyme" | "Rhyme, then meaning" as a two-button radio group. */
export function RhymeOrderToggleView({ order, onChange, label }: ViewProps) {
  return (
    <div>
      {label !== undefined && <span style={labelStyle}>{label}</span>}
      <div role="radiogroup" aria-label="Rhyme order" style={{ display: 'flex', gap: 4 }}>
        {RHYME_ORDERS.map((o) => (
          <button
            key={o}
            type="button"
            role="radio"
            aria-checked={order === o}
            onClick={() => onChange(o)}
            title={o === 'meaning' ? 'Group by rhyme strength, then sort each group by closeness in meaning' : 'Sort by rhyme score'}
            style={{ ...(order === o ? primaryBtnStyle : btnStyle), padding: '2px 8px', fontSize: 11 }}
          >
            {RHYME_ORDER_LABELS[o]}
          </button>
        ))}
      </div>
    </div>
  );
}

/** The toggle bound to the viewer's remembered order (shared by the sidebar, pattern columns and word bank). */
export default function RhymeOrderToggle({ label }: { label?: string }) {
  const order = useRhymeOrderStore((s) => s.order);
  const setOrder = useRhymeOrderStore((s) => s.setOrder);
  return <RhymeOrderToggleView order={order} onChange={setOrder} label={label} />;
}
