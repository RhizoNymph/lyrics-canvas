import { rhythmNote, rhythmPercent } from '@/lib/rhyme/rhythmFit';
import type { RhythmFitting } from '@/lib/rhyme/rhythmFit';
import { mutedStyle } from '../sidebarStyles';

/** Quiet rhythm-fit percent next to a row's score; nothing when the row has no rhythm fit. */
export function RhythmBadge({ fit }: { fit: number | null | undefined }) {
  const pct = rhythmPercent(fit);
  if (pct === null) return null;
  return (
    <span data-rhythm-fit style={{ fontFamily: 'monospace', fontSize: 10, color: '#8ab4d8' }} title={`Rhythm fit ${pct}%`}>
      ♩{pct}%
    </span>
  );
}

/** Why a requested rhythm fit was not applied; nothing otherwise. */
export function RhythmNote({ rhythm }: { rhythm: RhythmFitting }) {
  const text = rhythmNote(rhythm);
  if (text === null) return null;
  return (
    <div role="note" style={{ ...mutedStyle, fontStyle: 'italic' }}>
      {text}
    </div>
  );
}
