import { useState } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';
import { selectedSnippets } from '@/lib/rhyme/queryFromSelection';
import { defaultLastN, lastLinesRange, lastNMax } from '@/lib/rhyme/pattern';
import { PATTERN_MIN_LINES } from '@/types/rhymePattern';
import { btnStyle } from '../sidebarStyles';

interface ViewProps {
  n: number;
  max: number;
  onChange: (n: number) => void;
  onStart: () => void;
}

/** "Pattern (last N lines)" with a 2..max stepper. */
export function PatternShortcutView({ n, max, onChange, onStart }: ViewProps) {
  const step = (d: number) => onChange(Math.max(PATTERN_MIN_LINES, Math.min(max, n + d)));
  const small = { ...btnStyle, padding: '1px 6px', fontSize: 11 };
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 6 }}>
      <button type="button" onClick={onStart} style={small} title="Find where these lines rhyme with each other and suggest words for each place">
        Pattern (last {n} lines)
      </button>
      <button type="button" onClick={() => step(-1)} disabled={n <= PATTERN_MIN_LINES} style={small} aria-label="Fewer lines">
        −
      </button>
      <button type="button" onClick={() => step(1)} disabled={n >= max} style={small} aria-label="More lines">
        +
      </button>
    </div>
  );
}

/** Shown in the normal mode when exactly one snippet with 2+ lines with words is selected. */
export default function PatternShortcut() {
  const selectedIds = useCanvasStore((s) => s.selectedSnippetIds);
  const snippets = useCanvasStore((s) => s.snippets);
  const enter = useRhymePatternStore((s) => s.enter);
  const [chosen, setChosen] = useState<number | null>(null);

  const selected = selectedSnippets(selectedIds, snippets);
  if (selected.length !== 1) return null;
  const snippet = selected[0];
  const lines = snippet.grid_data?.lines ?? [];
  const fallback = defaultLastN(lines);
  if (fallback === null) return null;
  const max = lastNMax(lines);
  const n = Math.min(max, chosen ?? fallback);

  const start = () => {
    const range = lastLinesRange(lines, n);
    if (range !== null) enter(snippet.id, range);
  };
  return <PatternShortcutView n={n} max={max} onChange={setChosen} onStart={start} />;
}
