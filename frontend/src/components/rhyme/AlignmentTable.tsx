import type { RhymeCandidate, RhymeSyllable, RhymeUnit, SyllableAlignment } from '@/types/rhyme';
import { alignmentColor } from './rhymeColors';

function syllableText(unit: RhymeUnit | undefined, index: number | null): string {
  if (!unit || index === null) return '—';
  const syl: RhymeSyllable | undefined = unit.syllables[index];
  if (!syl) return '—';
  return [...syl.onset, syl.nucleus, ...syl.coda].join(' ');
}

const headStyle: React.CSSProperties = { color: '#8a8aaa', paddingRight: 6, textAlign: 'left' };
const cellStyle = (total: number): React.CSSProperties => ({
  background: alignmentColor(total),
  borderRadius: 3,
  padding: '2px 6px',
  textAlign: 'center',
  color: '#fff',
});

/** The candidate's aligned tail against the anchor, cell by cell, coloured by per-cell total. */
export default function AlignmentTable({ candidate, anchor }: { candidate: RhymeCandidate; anchor: RhymeUnit | undefined }) {
  // Alignment is right-to-left; display left-to-right like the text.
  const aligned: SyllableAlignment[] = [...candidate.alignment].reverse();
  if (aligned.length === 0) return <div style={{ color: '#8a8aaa', fontSize: 11 }}>No alignment.</div>;
  return (
    <div>
      <table style={{ borderCollapse: 'separate', borderSpacing: 3, fontFamily: 'monospace', fontSize: 11 }}>
        <tbody>
          <tr>
            <td style={headStyle}>cand</td>
            {aligned.map((a, i) => (
              <td key={`c${i}`} style={cellStyle(a.total)}>{syllableText(candidate, a.cand_index)}</td>
            ))}
          </tr>
          <tr>
            <td style={headStyle}>anchor</td>
            {aligned.map((a, i) => (
              <td key={`a${i}`} style={cellStyle(a.total)}>{syllableText(anchor, a.anchor_index)}</td>
            ))}
          </tr>
          <tr>
            <td style={headStyle}>total</td>
            {aligned.map((a, i) => (
              <td key={`t${i}`} style={{ textAlign: 'center', color: '#c0c0d8' }}>{a.total.toFixed(2)}</td>
            ))}
          </tr>
        </tbody>
      </table>
      <div style={{ color: '#8a8aaa', fontSize: 11, marginTop: 4 }}>
        {anchor ? `vs “${anchor.text}”` : 'anchor not echoed'} · stress {candidate.stress}
      </div>
    </div>
  );
}
