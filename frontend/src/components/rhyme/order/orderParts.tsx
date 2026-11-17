import { BAND_LABELS, MEANING_UNAVAILABLE_NOTE, similarityPercent } from '@/lib/rhyme/order';
import type { OrderPresentation, RhymeBand } from '@/lib/rhyme/order';
import { mutedStyle } from '../sidebarStyles';

/** Section header where the band changes in a meaning-ordered list. */
export function BandHeader({ band, compact = false }: { band: RhymeBand; compact?: boolean }) {
  return (
    <div
      role="heading"
      aria-level={4}
      data-band={band}
      style={{
        color: '#a5a5d0',
        fontSize: compact ? 9 : 10,
        fontWeight: 'bold',
        letterSpacing: 0.6,
        textTransform: 'uppercase',
        padding: compact ? '4px 6px 2px' : '8px 4px 2px',
        borderBottom: '1px solid #2a2a4a',
      }}
    >
      {BAND_LABELS[band]}
    </div>
  );
}

/** Quiet "meaning" percent next to a row's score; nothing when the row has no similarity. */
export function SimilarityBadge({ similarity }: { similarity: number | null | undefined }) {
  const pct = similarityPercent(similarity);
  if (pct === null) return null;
  return (
    <span data-similarity style={{ fontFamily: 'monospace', fontSize: 10, color: '#7a7a9a' }} title={`Meaning similarity ${pct}%`}>
      ≈{pct}%
    </span>
  );
}

/** "Meaning order unavailable" when meaning was asked but the server fell back to rhyme order. */
export function OrderNote({ presentation }: { presentation: OrderPresentation }) {
  if (presentation !== 'unavailable') return null;
  return (
    <div role="note" style={{ ...mutedStyle, fontStyle: 'italic' }}>
      {MEANING_UNAVAILABLE_NOTE}
    </div>
  );
}
