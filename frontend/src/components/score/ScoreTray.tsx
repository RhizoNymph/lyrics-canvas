/**
 * The tray of unplaced syllables, one strip per grid line that has any. A chip can be dragged onto the
 * staff (HTML drag and drop, payload SYLLABLE_DRAG_TYPE) or clicked to arm it, then placed by clicking
 * the staff or pressing Enter.
 */
import type { NoteRef } from './StaffRowView';
import { STAFF_COLORS } from './staffGeometry';

export const SYLLABLE_DRAG_TYPE = 'application/x-lyrics-syllable';

export function syllableDragData(note: NoteRef): string {
  return JSON.stringify({ line: note.line, s: note.s });
}

/** The syllable a drop carries, or null for anything else. */
export function parseSyllableDrag(data: string): NoteRef | null {
  if (data === '') return null;
  let value: unknown;
  try {
    value = JSON.parse(data);
  } catch (error) {
    if (error instanceof SyntaxError) return null;
    throw error;
  }
  if (typeof value !== 'object' || value === null) return null;
  const { line, s } = value as { line?: unknown; s?: unknown };
  return Number.isInteger(line) && Number.isInteger(s) ? { line: line as number, s: s as number } : null;
}

interface Props {
  tray: readonly (readonly number[])[];
  labels: readonly (readonly string[])[];
  armed: NoteRef | null;
  scale: number;
  onArm: (note: NoteRef) => void;
}

export default function ScoreTray({ tray, labels, armed, scale: s, onArm }: Props) {
  const lines = tray.map((ordinals, line) => ({ line, ordinals })).filter((entry) => entry.ordinals.length > 0);
  if (lines.length === 0) {
    return <div style={{ fontSize: 10 * s, color: '#4a4a6a', padding: `${4 * s}px 0` }}>Every syllable is on the staff.</div>;
  }
  return (
    <div style={{ marginTop: 6 * s, borderTop: '1px solid rgba(74, 74, 106, 0.5)', paddingTop: 4 * s }} aria-label="Unplaced syllables">
      {lines.map(({ line, ordinals }) => (
        <div key={line} style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 4 * s, marginBottom: 3 * s }}>
          <span style={{ fontSize: 9 * s, fontFamily: 'monospace', color: STAFF_COLORS.lineMark, minWidth: 22 * s }}>L{line + 1}</span>
          {ordinals.map((ordinal) => {
            const isArmed = armed !== null && armed.line === line && armed.s === ordinal;
            return (
              <button
                key={ordinal}
                type="button"
                draggable
                onDragStart={(e) => {
                  e.dataTransfer.setData(SYLLABLE_DRAG_TYPE, syllableDragData({ line, s: ordinal }));
                  e.dataTransfer.effectAllowed = 'move';
                }}
                onClick={() => onArm({ line, s: ordinal })}
                title="Drag onto the staff, or click and then click the staff"
                style={{
                  fontFamily: 'monospace',
                  fontSize: 11 * s,
                  padding: `${1 * s}px ${5 * s}px`,
                  borderRadius: 3,
                  border: `1px solid ${isArmed ? STAFF_COLORS.noteSelected : 'rgba(165, 180, 252, 0.4)'}`,
                  background: isArmed ? 'rgba(251, 191, 36, 0.15)' : 'rgba(99, 102, 241, 0.12)',
                  color: STAFF_COLORS.text,
                  cursor: 'grab',
                }}
              >
                {labels[line]?.[ordinal] || '?'}
              </button>
            );
          })}
        </div>
      ))}
    </div>
  );
}
