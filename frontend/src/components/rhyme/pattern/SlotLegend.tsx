import type { SlotView } from '@/lib/rhyme/pattern';
import { slotAlpha, slotColor } from '../rhymeColors';
import { monoStyle } from '../sidebarStyles';

interface Props {
  views: readonly SlotView[];
  onToggle: (slotId: string) => void;
  onDrop: (slotId: string) => void;
}

/** Legend row: `A  EH D IY  3/3` per slot; the chip toggles the slot on / off, × drops it. */
export default function SlotLegend({ views, onToggle, onDrop }: Props) {
  if (views.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {views.map((v) => {
        const colour = slotColor(v.index);
        return (
          <span
            key={v.id}
            data-slot-id={v.id}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 4, border: `1px solid ${colour}`, borderRadius: 12, padding: '1px 6px', fontSize: 11, opacity: slotAlpha(v.on, v.full) }}
          >
            <button
              type="button"
              aria-pressed={v.on}
              onClick={() => onToggle(v.id)}
              title={v.on ? 'Turn off (not searched)' : 'Turn on'}
              style={{ background: 'none', border: 'none', color: '#e0e0e0', cursor: 'pointer', padding: 0, fontSize: 11, display: 'inline-flex', gap: 6 }}
            >
              <b style={{ color: colour }}>{v.letter}</b>
              {v.key !== null && <span style={{ ...monoStyle, color: '#c0c0d8' }}>{v.key}</span>}
              <span style={{ color: '#8a8aaa' }}>
                {v.coverage}/{v.lineCount}
              </span>
            </button>
            <button type="button" onClick={() => onDrop(v.id)} title="Drop this slot" aria-label={`Drop slot ${v.letter}`} style={{ background: 'none', border: 'none', color: '#aaa', cursor: 'pointer', padding: 0, fontSize: 12 }}>
              ×
            </button>
          </span>
        );
      })}
    </div>
  );
}
