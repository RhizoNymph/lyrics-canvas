import { useRhymeRhythmFitStore } from '@/stores/rhymeRhythmFitStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import { useCanvasStore } from '@/stores/canvasStore';
import { anchorSnippets } from '@/lib/rhyme/queryFromSelection';
import { mutedStyle } from '../sidebarStyles';

interface ViewProps {
  on: boolean;
  onChange: (on: boolean) => void;
}

/** "Fit rhythm" checkbox: rank candidates by how their stress fits the slot's rhythm on the staff. */
export function RhythmFitToggleView({ on, onChange }: ViewProps) {
  return (
    <label
      style={{ ...mutedStyle, display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 12 }}
      title="Only candidates with the slot's syllable count, ranked inside each rhyme band by how their stress fits the slot's rhythm on the snippet's staff"
    >
      <input type="checkbox" checked={on} onChange={(e) => onChange(e.target.checked)} /> Fit rhythm
    </label>
  );
}

/**
 * The switch bound to the viewer's remembered choice, shown only while a selected snippet anchors the
 * query (rhythm fit needs the snippet's score; typed text has none).
 */
export default function RhythmFitToggle() {
  const on = useRhymeRhythmFitStore((s) => s.on);
  const setOn = useRhymeRhythmFitStore((s) => s.setOn);
  const excluded = useRhymeStore((s) => s.excludedAnchorIds);
  const selected = useCanvasStore((s) => s.selectedSnippetIds);
  const snippets = useCanvasStore((s) => s.snippets);
  if (anchorSnippets(selected, snippets, excluded).length === 0) return null;
  return <RhythmFitToggleView on={on} onChange={setOn} />;
}
