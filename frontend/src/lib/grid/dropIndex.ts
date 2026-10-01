/** Gap index (0..cellCount) nearest to `x`, measured from the left edge of cell 0. */
export function gapFromX(x: number, cellWidth: number, cellCount: number): number {
  if (cellWidth <= 0) return 0;
  const gap = Math.round(x / cellWidth);
  return Math.min(Math.max(gap, 0), cellCount);
}

/**
 * Translate a drop gap (in the row's original cell coordinates) into the `to`
 * argument of `moveChunk` for the chunk `[start, end)`. Null when the drop
 * would leave the chunk where it is.
 */
export function moveTargetFromGap(gap: number, start: number, end: number): number | null {
  if (gap >= start && gap <= end) return null;
  return gap < start ? gap : gap - (end - start);
}
