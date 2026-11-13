import { useRhymeStore } from '@/stores/rhymeStore';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';
import { PATTERN_DETECT_MIN_SCORE_FLOOR } from '@/types/rhymePattern';
import NotesTagFilter from '../NotesTagFilter';
import SourceToggles from '../SourceToggles';
import { labelStyle } from '../sidebarStyles';

/** Detection threshold slider (re-detects, discarding slot edits). */
export function DetectThreshold() {
  const value = useRhymePatternStore((s) => s.detectMinScore);
  const setValue = useRhymePatternStore((s) => s.setDetectMinScore);
  return (
    <div>
      <label style={labelStyle} title="How closely the lines must rhyme with each other to form a slot; changing it detects again">
        Detection threshold {value.toFixed(2)}
      </label>
      <input type="range" min={PATTERN_DETECT_MIN_SCORE_FLOOR} max={1} step={0.05} value={value} onChange={(e) => setValue(Number(e.target.value))} style={{ width: '100%' }} />
    </div>
  );
}

/** The rhyme controls the pattern view shares with the normal mode: min score, sources, rare words, notes tag filter. */
export default function PatternControls() {
  const minScore = useRhymeStore((s) => s.minScore);
  const setMinScore = useRhymeStore((s) => s.setMinScore);
  return (
    <details>
      <summary style={{ cursor: 'pointer', fontSize: 12, color: '#c0c0d8' }}>Search options</summary>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 6 }}>
        <div>
          <label style={labelStyle}>Min score {minScore.toFixed(2)}</label>
          <input type="range" min={0} max={1} step={0.05} value={minScore} onChange={(e) => setMinScore(Number(e.target.value))} style={{ width: '100%' }} />
        </div>
        <SourceToggles />
        <NotesTagFilter />
        <DetectThreshold />
      </div>
    </details>
  );
}
