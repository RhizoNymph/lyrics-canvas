import { useMemo } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { candidateCardSize, candidateTopLeft } from '@/lib/rhyme/candidateGeometry';
import type { RhymeSyllable, RhymeUnit, SyllableAlignment } from '@/types/rhyme';
import { alignmentColor, PROVENANCE_COLORS } from './rhymeColors';

interface Props {
  viewport: { x: number; y: number; zoom: number };
}

function syllableText(unit: RhymeUnit | undefined, index: number | null): string {
  if (!unit || index === null) return '—';
  const syl: RhymeSyllable | undefined = unit.syllables[index];
  if (!syl) return '—';
  return [...syl.onset, syl.nucleus, ...syl.coda].join(' ');
}

const btnStyle: React.CSSProperties = {
  border: 'none',
  borderRadius: 4,
  padding: '4px 10px',
  cursor: 'pointer',
  fontSize: 12,
  color: '#fff',
};

/** Hover/pinned tooltip for one candidate: score, depth, aligned tail vs anchor, Promote/Dismiss. */
export default function CandidateTooltip({ viewport }: Props) {
  const { candidates, anchors, positions, hoveredId, pinnedId, setHovered, setPinned, promote, dismiss } = useRhymeStore();
  const id = pinnedId ?? hoveredId;
  const candidate = useMemo(() => (id ? candidates.find((c) => c.id === id) : undefined), [id, candidates]);
  if (!id || !candidate) return null;
  const center = positions.get(id);
  if (!center) return null;

  const size = candidateCardSize(candidate);
  const topLeft = candidateTopLeft(center, size);
  const left = (topLeft.x + viewport.x) * viewport.zoom;
  const top = (topLeft.y + size.height + viewport.y) * viewport.zoom + 6;
  const anchor = anchors.find((a) => a.id === candidate.best_anchor_id);
  // Alignment is right-to-left; display left-to-right like the text.
  const aligned: SyllableAlignment[] = [...candidate.alignment].reverse();
  const pinned = pinnedId === id;

  return (
    <div
      onMouseEnter={() => setHovered(id)}
      onMouseDown={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
      style={{
        position: 'absolute',
        left,
        top,
        minWidth: 200,
        maxWidth: 360,
        background: 'rgba(22, 22, 42, 0.97)',
        border: `1px solid ${pinned ? '#ffffff' : PROVENANCE_COLORS[candidate.provenance]}`,
        borderRadius: 6,
        padding: 10,
        color: '#e0e0e0',
        fontSize: 12,
        zIndex: 950,
        pointerEvents: 'auto',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12 }}>
        <span style={{ fontWeight: 'bold', fontSize: 14 }}>{candidate.text}</span>
        <span style={{ fontFamily: 'monospace', color: '#8a8aaa' }}>
          {candidate.score.toFixed(2)} · depth {candidate.match_depth}
        </span>
      </div>
      <div style={{ color: '#8a8aaa', marginTop: 2 }}>
        {candidate.source} · {candidate.provenance} · stress {candidate.stress}
        {!candidate.fits_slot && <span style={{ color: '#f59e0b' }}> · does not fit slot</span>}
      </div>

      {aligned.length > 0 && (
        <table style={{ borderCollapse: 'separate', borderSpacing: 3, marginTop: 8, fontFamily: 'monospace', fontSize: 11 }}>
          <tbody>
            <tr>
              <td style={{ color: '#8a8aaa', paddingRight: 6 }}>cand</td>
              {aligned.map((a, i) => (
                <td key={`c${i}`} style={{ background: alignmentColor(a.total), borderRadius: 3, padding: '2px 6px', textAlign: 'center' }}>
                  {syllableText(candidate, a.cand_index)}
                </td>
              ))}
            </tr>
            <tr>
              <td style={{ color: '#8a8aaa', paddingRight: 6 }}>anchor</td>
              {aligned.map((a, i) => (
                <td key={`a${i}`} style={{ background: alignmentColor(a.total), borderRadius: 3, padding: '2px 6px', textAlign: 'center' }}>
                  {syllableText(anchor, a.anchor_index)}
                </td>
              ))}
            </tr>
            <tr>
              <td style={{ color: '#8a8aaa', paddingRight: 6 }}>total</td>
              {aligned.map((a, i) => (
                <td key={`t${i}`} style={{ textAlign: 'center', color: '#c0c0d8' }}>{a.total.toFixed(2)}</td>
              ))}
            </tr>
          </tbody>
        </table>
      )}
      {anchor && <div style={{ color: '#8a8aaa', marginTop: 4 }}>vs “{anchor.text}”</div>}

      <div style={{ display: 'flex', gap: 6, marginTop: 8, justifyContent: 'flex-end' }}>
        <button onClick={() => void promote(id)} style={{ ...btnStyle, background: '#6366f1' }}>Promote</button>
        <button onClick={() => dismiss(id)} style={{ ...btnStyle, background: '#3a3a5a' }}>Dismiss</button>
        {pinned && (
          <button onClick={() => setPinned(null)} style={{ ...btnStyle, background: 'transparent', color: '#8a8aaa' }} title="Unpin">×</button>
        )}
      </div>
    </div>
  );
}
