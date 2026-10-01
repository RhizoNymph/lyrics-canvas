import type { GridLine } from '@/types';
import type { AnchorRange } from '@/types/rhyme';
import { cellInRange } from '@/lib/rhyme/anchorRange';

interface Props {
  line: GridLine;
  /** Highlights the cells of these words (the line index is the caller's concern). */
  selected?: AnchorRange | null;
  /** Makes word cells clickable (rests are never selectable); receives the cell's `word_index`. */
  onWordClick?: (wordIndex: number) => void;
}

/** Compact one-row beat grid: syllable text per cell, stressed bold, rests as "·". */
export default function SyllableStrip({ line, selected = null, onWordClick }: Props) {
  return (
    <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 2, fontFamily: 'monospace', fontSize: 11 }}>
      {line.cells.map((cell, i) => {
        const rest = cell.rest === true;
        const stressed = !rest && (cell.stress === 1 || cell.stress === 2);
        const clickable = onWordClick !== undefined && !rest && cell.word_index !== undefined;
        const inRange = selected !== null && cellInRange(cell, selected);
        return (
          <span
            key={i}
            onClick={clickable ? () => onWordClick(cell.word_index as number) : undefined}
            title={clickable ? 'Click to set the start / end word' : undefined}
            style={{
              padding: '1px 5px',
              borderRadius: 3,
              background: inRange ? '#0ea5e9' : '#242448',
              color: rest ? '#555577' : inRange ? '#0b1120' : stressed ? '#ffffff' : '#d0d0e8',
              fontWeight: stressed ? 'bold' : 'normal',
              cursor: clickable ? 'pointer' : 'default',
              userSelect: 'none',
            }}
          >
            {rest ? '·' : cell.syllable ?? ''}
          </span>
        );
      })}
    </span>
  );
}
