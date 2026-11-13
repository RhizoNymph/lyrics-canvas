import type { TrayEntry } from '@/lib/rhyme/pattern';
import { TRAY_GAP, pickTexts } from '@/lib/rhyme/pattern';
import { slotColor } from '../rhymeColors';
import { btnStyle, mutedStyle, primaryBtnStyle } from '../sidebarStyles';

interface Props {
  entries: readonly TrayEntry[];
  /** Slot order index per slot id (for the colour). */
  indexOf: (slotId: string) => number;
  onMakeSnippet: () => void;
  onAddToBank: () => void;
  onClear: () => void;
}

/** The picks in slot order with gaps (`steady · ___ · strong`) and the tuple actions. */
export default function TupleTray({ entries, indexOf, onMakeSnippet, onAddToBank, onClear }: Props) {
  if (entries.length === 0) return null;
  const any = pickTexts(entries).length > 0;
  const dim = { opacity: any ? 1 : 0.5 };
  return (
    <div style={{ padding: 8, border: '1px solid #3a3a5a', borderRadius: 4, background: '#1f1f3a' }}>
      <div aria-label="Tuple" style={{ fontSize: 13, display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'baseline' }}>
        {entries.map((e, i) => (
          <span key={e.slotId} style={{ display: 'inline-flex', gap: 4, alignItems: 'baseline' }}>
            {i > 0 && <span style={mutedStyle}>·</span>}
            <span title={`Slot ${e.letter}`} style={{ color: e.text === null ? '#555577' : slotColor(indexOf(e.slotId)), fontWeight: e.text === null ? 'normal' : 'bold' }}>
              {e.text ?? TRAY_GAP}
            </span>
          </span>
        ))}
      </div>
      {!any && <div style={{ ...mutedStyle, marginTop: 2 }}>Tap a row in a list to pick it for its slot.</div>}
      <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
        <button type="button" onClick={onMakeSnippet} disabled={!any} style={{ ...primaryBtnStyle, fontSize: 11, ...dim }} title="Create a snippet next to the anchor with the picks as one line">
          Make snippet
        </button>
        <button type="button" onClick={onAddToBank} disabled={!any} style={{ ...btnStyle, fontSize: 11, ...dim }}>
          Add picks to word bank
        </button>
        <button type="button" onClick={onClear} disabled={!any} style={{ ...btnStyle, fontSize: 11, ...dim }}>
          Clear
        </button>
      </div>
    </div>
  );
}
