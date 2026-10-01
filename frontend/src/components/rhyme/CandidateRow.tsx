import type { RhymeCandidate, RhymeUnit } from '@/types/rhyme';
import SyllableStrip from './SyllableStrip';
import AlignmentTable from './AlignmentTable';
import { PROVENANCE_COLORS, PROVENANCE_LABELS, NON_FIT_ALPHA } from './rhymeColors';
import { btnStyle, primaryBtnStyle, monoStyle } from './sidebarStyles';

interface Props {
  candidate: RhymeCandidate;
  anchor: RhymeUnit | undefined;
  /** Whether a slot was sent with the query; without one "fits" means nothing. */
  hasSlot: boolean;
  expanded: boolean;
  onToggle: () => void;
  onChain: () => void;
  onMaterialize: () => void;
  onDismiss: () => void;
}

/** One result: text, syllable strip, score, depth, fit badge, source; expands to the alignment. */
export default function CandidateRow({ candidate, anchor, hasSlot, expanded, onToggle, onChain, onMaterialize, onDismiss }: Props) {
  const colour = PROVENANCE_COLORS[candidate.provenance];
  const dimmed = hasSlot && !candidate.fits_slot;
  return (
    <div
      style={{
        borderBottom: '1px solid #22223f',
        padding: '8px 4px',
        opacity: dimmed ? NON_FIT_ALPHA : 1,
        borderLeft: `3px solid ${colour}`,
        paddingLeft: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, cursor: 'pointer' }} onClick={onToggle}>
        <span
          onClick={(e) => {
            e.stopPropagation();
            onChain();
          }}
          title="Search rhymes for this instead"
          style={{ fontWeight: 'bold', fontSize: 13, color: '#e0e0e0', textDecoration: 'underline dotted', textUnderlineOffset: 3 }}
        >
          {candidate.text}
        </span>
        <span style={{ flex: 1 }} />
        <span style={{ ...monoStyle, fontSize: 11, color: '#c0c0d8' }}>{candidate.score.toFixed(2)}</span>
        <span style={{ ...monoStyle, fontSize: 11, color: '#8a8aaa' }}>d{candidate.match_depth}</span>
        {hasSlot && candidate.fits_slot && (
          <span style={{ fontSize: 10, color: '#4ade80', border: '1px solid #4ade80', borderRadius: 3, padding: '0 4px' }}>fits slot</span>
        )}
        <span style={{ fontSize: 10, fontWeight: 'bold', color: colour }} title={`${candidate.source} · ${candidate.provenance}`}>
          {PROVENANCE_LABELS[candidate.provenance]}
        </span>
      </div>
      <div style={{ marginTop: 4, overflowX: 'auto' }}>
        <SyllableStrip line={candidate.grid_line} />
      </div>
      {expanded && (
        <div style={{ marginTop: 8 }}>
          <AlignmentTable candidate={candidate} anchor={anchor} />
        </div>
      )}
      <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end', marginTop: 6 }}>
        <button onClick={onMaterialize} style={{ ...primaryBtnStyle, padding: '2px 8px', fontSize: 11 }} title="Create a snippet from this on the canvas">
          Materialize
        </button>
        <button onClick={onDismiss} style={{ ...btnStyle, padding: '2px 8px', fontSize: 11 }} title="Hide for this session">
          Dismiss
        </button>
      </div>
    </div>
  );
}
