import type { GridData, GridCell } from '@/types';

interface Props {
  gridData: GridData;
  zoom?: number;
}

export default function BeatGridDisplay({ gridData, zoom = 1 }: Props) {
  if (!gridData?.lines?.length) return null;

  const maxCells = Math.max(...gridData.lines.map((l) => l.cells.length), 1);

  return (
    <div style={{ overflowX: 'auto', fontSize: 13 * zoom }}>
      <table
        style={{
          borderCollapse: 'collapse',
          fontFamily: 'monospace',
          width: '100%',
        }}
      >
        <thead>
          <tr>
            {Array.from({ length: maxCells }, (_, i) => (
              <th
                key={i}
                style={{
                  padding: '2px 8px',
                  color: '#555',
                  fontWeight: 'normal',
                  fontSize: '0.8em',
                  borderBottom: '1px solid #2a2a4a',
                  textAlign: 'center',
                  minWidth: 50 * zoom,
                }}
              >
                {i + 1}
              </th>
            ))}
            <th
              style={{
                padding: '2px 8px',
                color: '#666',
                fontWeight: 'normal',
                fontSize: '0.8em',
                borderBottom: '1px solid #2a2a4a',
                textAlign: 'right',
              }}
            >
              #
            </th>
          </tr>
        </thead>
        <tbody>
          {gridData.lines.map((line, row) => (
            <tr key={row}>
              {Array.from({ length: maxCells }, (_, col) => {
                const cell: GridCell | undefined = line.cells[col];
                return (
                  <td
                    key={col}
                    style={{
                      padding: '3px 8px',
                      borderRight: '1px solid #1a1a3a',
                      textAlign: 'center',
                      color: cell?.rest ? '#444' : cell?.stress && cell.stress > 0 ? '#fff' : '#bbb',
                      fontWeight: cell?.stress && cell.stress > 0 ? 'bold' : 'normal',
                    }}
                  >
                    {cell?.rest ? '·' : cell?.syllable || ''}
                  </td>
                );
              })}
              <td
                style={{
                  padding: '3px 8px',
                  textAlign: 'right',
                  color: '#666',
                  fontSize: '0.85em',
                }}
              >
                {line.syllable_count}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
