import type { GridData } from '@/types';

/** Move `lines[from]` so it sits at index `to`. Same grid for a no-op or out-of-range request. */
export function reorderLines(grid: GridData, from: number, to: number): GridData {
  const n = grid.lines.length;
  if (from === to || from < 0 || from >= n || to < 0 || to >= n) return grid;
  const lines = [...grid.lines];
  const [moved] = lines.splice(from, 1);
  lines.splice(to, 0, moved);
  return { lines };
}
