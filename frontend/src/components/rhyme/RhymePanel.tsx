import { useEffect, useState } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { parseManualSlot } from '@/lib/rhyme/slotFit';
import { RHYME_K_VALUES, RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeK } from '@/types/rhyme';

const panelStyle: React.CSSProperties = {
  position: 'absolute',
  top: 12,
  left: 12,
  width: 250,
  background: 'rgba(22, 22, 42, 0.96)',
  border: '1px solid #2a2a4a',
  borderRadius: 8,
  padding: 12,
  color: '#e0e0e0',
  fontSize: 12,
  zIndex: 900,
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const inputStyle: React.CSSProperties = {
  background: '#2a2a4a',
  color: '#e0e0e0',
  border: '1px solid #3a3a5a',
  borderRadius: 4,
  padding: '4px 8px',
  fontSize: 12,
  width: '100%',
  boxSizing: 'border-box',
};

const btnStyle: React.CSSProperties = {
  background: '#2a2a4a',
  color: '#ccc',
  border: '1px solid #3a3a5a',
  borderRadius: 4,
  padding: '4px 10px',
  cursor: 'pointer',
  fontSize: 12,
};

const labelStyle: React.CSSProperties = { color: '#8a8aaa', fontSize: 11, display: 'block', marginBottom: 2 };

/** HTML overlay with the rhyme-view controls. Mounted inside CanvasViewport while the view is active. */
export default function RhymePanel() {
  const {
    typedAnchor, setTypedAnchor, k, setK, minScore, setMinScore,
    slot, slotMode, setSlot, setSlotMode, sources, toggleSource,
    candidates, total, offset, loading, error, loadMore, exit,
  } = useRhymeStore();

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
    // Deliberately keyed on the raw inputs only: `manual` is a fresh object every render.
  }, [slotMode, manualCount, manualStress]);

  const shownStart = candidates.length === 0 ? 0 : offset + 1;
  const shownEnd = Math.min(offset + RHYME_PAGE_SIZE, candidates.length);
  const canLoadMore = !loading && offset + RHYME_PAGE_SIZE < total;
  const manualError =
    slotMode === 'manual' && (manualCount !== '' || manualStress !== '') && !manual.ok
      ? manual.reason === 'stress'
        ? 'Stress must match ^[012]+$'
        : 'Count must equal the stress length'
      : null;

  return (
    <div style={panelStyle} onMouseDown={(e) => e.stopPropagation()} onWheel={(e) => e.stopPropagation()}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', fontSize: 13 }}>Rhyme View</span>
        <button onClick={exit} style={btnStyle} title="Exit rhyme view">Exit</button>
      </div>

      <div>
        <label style={labelStyle}>Anchor phrase (optional)</label>
        <input
          value={typedAnchor}
          onChange={(e) => setTypedAnchor(e.target.value)}
          placeholder="e.g. door hinge"
          style={inputStyle}
        />
      </div>

      <div style={{ display: 'flex', gap: 8 }}>
        <div style={{ flex: '0 0 60px' }}>
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
        <div style={{ display: 'flex', gap: 10 }}>
          <label style={{ cursor: 'pointer' }}>
            <input type="radio" checked={slotMode === 'from-selection'} onChange={() => setSlotMode('from-selection')} /> from selection
          </label>
          <label style={{ cursor: 'pointer' }}>
            <input type="radio" checked={slotMode === 'manual'} onChange={() => setSlotMode('manual')} /> manual
          </label>
        </div>
        {slotMode === 'from-selection' ? (
          <div style={{ color: '#8a8aaa', fontFamily: 'monospace', marginTop: 4 }}>
            {slot ? `${slot.stress} (${slot.syllable_count})` : 'none'}
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
            <input
              value={manualCount}
              onChange={(e) => setManualCount(e.target.value)}
              placeholder="count"
              inputMode="numeric"
              style={{ ...inputStyle, width: 60 }}
            />
            <input
              value={manualStress}
              onChange={(e) => setManualStress(e.target.value)}
              placeholder="stress e.g. 1010"
              style={{ ...inputStyle, fontFamily: 'monospace' }}
            />
          </div>
        )}
        {manualError && <div style={{ color: '#f87171', marginTop: 2 }}>{manualError}</div>}
      </div>

      <div style={{ display: 'flex', gap: 12 }}>
        <label style={{ cursor: 'pointer' }}>
          <input type="checkbox" checked={sources.notes} onChange={() => toggleSource('notes')} /> notes
        </label>
        <label style={{ cursor: 'pointer' }}>
          <input type="checkbox" checked={sources.dictionary} onChange={() => toggleSource('dictionary')} /> dictionary
        </label>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ color: '#8a8aaa' }}>
          {loading ? 'Searching…' : total === 0 ? 'No candidates' : `Showing ${shownStart}–${shownEnd} of ${total}`}
        </span>
        <button onClick={() => void loadMore()} disabled={!canLoadMore} style={{ ...btnStyle, opacity: canLoadMore ? 1 : 0.5 }}>
          More
        </button>
      </div>

      {error && (
        <div style={{ color: '#f87171' }}>
          {error.operation === 'promote' ? 'Promote failed' : 'Query failed'} ({error.kind}): {error.message}
        </div>
      )}
    </div>
  );
}
