import type { PatternCandidate } from '@/types/rhymePattern';
import AddToBankButton from '@/components/wordBank/AddToBankButton';
import { PROVENANCE_COLORS } from '../rhymeColors';
import { btnStyle, monoStyle } from '../sidebarStyles';

interface Props {
  candidate: PatternCandidate;
  picked: boolean;
  colour: string;
  onPick: () => void;
  onDismiss: () => void;
}

/** Source badge: the user's own line, or the dictionary. */
export function sourceLabel(c: PatternCandidate): string {
  return c.source === 'notes' ? 'my line' : 'dict';
}

/** One slot candidate: text, score (the weakest member's), fit marker, source; tap picks it for the tuple. */
export default function PatternCandidateRow({ candidate, picked, colour, onPick, onDismiss }: Props) {
  return (
    <div
      data-candidate-id={candidate.id}
      aria-selected={picked}
      onClick={onPick}
      title={`Scores per member: ${candidate.member_scores.map((s) => s.toFixed(2)).join(', ')}`}
      style={{
        padding: '5px 6px',
        borderBottom: '1px solid #22223f',
        borderLeft: `3px solid ${picked ? colour : PROVENANCE_COLORS[candidate.provenance]}`,
        background: picked ? '#2a2a4a' : 'transparent',
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
        <span style={{ fontWeight: 'bold', fontSize: 12, color: '#e0e0e0', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{candidate.text}</span>
        <span style={{ ...monoStyle, fontSize: 10, color: '#c0c0d8' }}>{candidate.score.toFixed(2)}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }} onClick={(e) => e.stopPropagation()}>
        {candidate.fits_slot && (
          <span style={{ fontSize: 9, color: '#4ade80', border: '1px solid #4ade80', borderRadius: 3, padding: '0 3px' }} title="Fits the slot's syllables and stress">
            fits
          </span>
        )}
        <span style={{ fontSize: 9, fontWeight: 'bold', color: PROVENANCE_COLORS[candidate.provenance] }}>{sourceLabel(candidate)}</span>
        <span style={{ flex: 1 }} />
        <AddToBankButton text={candidate.text} />
        <button type="button" onClick={onDismiss} title="Hide for this session" aria-label="Dismiss" style={{ ...btnStyle, padding: '0 5px', fontSize: 11 }}>
          ×
        </button>
      </div>
    </div>
  );
}
