import { useEffect, useState } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { parseManualSlot } from '@/lib/rhyme/slotFit';
import { RHYME_K_VALUES } from '@/types/rhyme';
import type { RhymeK } from '@/types/rhyme';
import AnchorChips from './AnchorChips';
import { inputStyle, labelStyle, mutedStyle, errorStyle, monoStyle } from './sidebarStyles';

/** Search box, anchor chips, k / min score / slot / sources / include-rare. Changes re-query via useRhymeQuery. */
export default function QueryControls() {
  const typedAnchor = useRhymeStore((s) => s.typedAnchor);
  const setTypedAnchor = useRhymeStore((s) => s.setTypedAnchor);
  const runQuery = useRhymeStore((s) => s.runQuery);
  const anchors = useRhymeStore((s) => s.anchors);
  const k = useRhymeStore((s) => s.k);
  const setK = useRhymeStore((s) => s.setK);
  const minScore = useRhymeStore((s) => s.minScore);
  const setMinScore = useRhymeStore((s) => s.setMinScore);
  const slot = useRhymeStore((s) => s.slot);
  const slotMode = useRhymeStore((s) => s.slotMode);
  const setSlot = useRhymeStore((s) => s.setSlot);
  const setSlotMode = useRhymeStore((s) => s.setSlotMode);
  const sources = useRhymeStore((s) => s.sources);
  const toggleSource = useRhymeStore((s) => s.toggleSource);
  const includeRare = useRhymeStore((s) => s.includeRare);
  const setIncludeRare = useRhymeStore((s) => s.setIncludeRare);

  const [manualCount, setManualCount] = useState(slot ? String(slot.syllable_count) : '');
  const [manualStress, setManualStress] = useState(slot ? slot.stress : '');
  const manual = parseManualSlot(manualCount, manualStress);

  useEffect(() => {
    if (slotMode !== 'manual') return;
    if (manualCount === '' && manualStress === '') {
      setSlot(null);
      return;
    }
    setSlot(manual.ok ? manual.slot : null);
    // Keyed on the raw inputs only: `manual` is a fresh object every render.
  }, [slotMode, manualCount, manualStress]);

  const typedKey = anchors.find((a) => a.provenance === 'generated')?.rhyme_key.join(' ');
  const manualError =
    slotMode === 'manual' && (manualCount !== '' || manualStress !== '') && !manual.ok
      ? manual.reason === 'stress'
        ? 'Stress must match ^[012]+$'
        : 'Count must equal the stress length'
      : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div>
        <label style={labelStyle}>Search</label>
        <input
          value={typedAnchor}
          onChange={(e) => setTypedAnchor(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void runQuery();
          }}
          placeholder="Type a word or phrase, e.g. door hinge"
          style={inputStyle}
          autoFocus
        />
        {typedKey && typedAnchor.trim() && (
          <div style={{ ...mutedStyle, ...monoStyle, marginTop: 2 }}>key: {typedKey}</div>
        )}
      </div>

      <div>
        <label style={labelStyle}>Anchors from selection</label>
        <AnchorChips />
      </div>

      <div style={{ display: 'flex', gap: 10 }}>
        <div style={{ flex: '0 0 56px' }}>
          <label style={labelStyle}>k</label>
          <select value={k} onChange={(e) => setK(Number(e.target.value) as RhymeK)} style={inputStyle}>
            {RHYME_K_VALUES.map((v) => (
              <option key={v} value={v}>{v}</option>
            ))}
          </select>
        </div>
        <div style={{ flex: 1 }}>
          <label style={labelStyle}>Min score {minScore.toFixed(2)}</label>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={minScore}
            onChange={(e) => setMinScore(Number(e.target.value))}
            style={{ width: '100%' }}
          />
        </div>
      </div>

      <div>
        <label style={labelStyle}>Slot</label>
        <div style={{ display: 'flex', gap: 10, fontSize: 12 }}>
          <label style={{ cursor: 'pointer' }}>
            <input type="radio" checked={slotMode === 'from-selection'} onChange={() => setSlotMode('from-selection')} /> from selection
          </label>
          <label style={{ cursor: 'pointer' }}>
            <input type="radio" checked={slotMode === 'manual'} onChange={() => setSlotMode('manual')} /> manual
          </label>
        </div>
        {slotMode === 'from-selection' ? (
          <div style={{ ...mutedStyle, ...monoStyle, marginTop: 4 }}>
            {slot ? `${slot.stress} (${slot.syllable_count} syllables)` : 'none'}
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
            <input
              value={manualCount}
              onChange={(e) => setManualCount(e.target.value)}
              placeholder="count"
              inputMode="numeric"
              style={{ ...inputStyle, width: 64 }}
            />
            <input
              value={manualStress}
              onChange={(e) => setManualStress(e.target.value)}
              placeholder="stress e.g. 1010"
              style={{ ...inputStyle, ...monoStyle }}
            />
          </div>
        )}
        {manualError && <div style={{ ...errorStyle, marginTop: 2 }}>{manualError}</div>}
      </div>

      <div style={{ display: 'flex', gap: 12, fontSize: 12, flexWrap: 'wrap' }}>
        <label style={{ cursor: 'pointer' }}>
          <input type="checkbox" checked={sources.notes} onChange={() => toggleSource('notes')} /> notes
        </label>
        <label style={{ cursor: 'pointer' }}>
          <input type="checkbox" checked={sources.dictionary} onChange={() => toggleSource('dictionary')} /> dictionary
        </label>
        <label style={{ cursor: 'pointer' }} title="Include words below Zipf frequency 3">
          <input type="checkbox" checked={includeRare} onChange={(e) => setIncludeRare(e.target.checked)} /> rare words
        </label>
      </div>
    </div>
  );
}
