import type { GridLine } from '@/types';

/** Compact one-row beat grid: syllable text per cell, stressed bold, rests as "·". */
export default function SyllableStrip({ line }: { line: GridLine }) {
  return (
    <span style={{ display: 'inline-flex', gap: 2, fontFamily: 'monospace', fontSize: 11 }}>
      {line.cells.map((cell, i) => {
        const rest = cell.rest === true;
        const stressed = !rest && (cell.stress === 1 || cell.stress === 2);
        return (
          <span
            key={i}
            style={{
              padding: '1px 5px',
              borderRadius: 3,
              background: '#242448',
              color: rest ? '#555577' : stressed ? '#ffffff' : '#d0d0e8',
              fontWeight: stressed ? 'bold' : 'normal',
            }}
          >
            {rest ? '·' : cell.syllable ?? ''}
          </span>
        );
      })}
    </span>
  );
}
