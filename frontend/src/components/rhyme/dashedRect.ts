import type { Graphics as PixiGraphics } from 'pixi.js';

/**
 * Pixi v7 has no dashed line style; this strokes the four straight edges of a rounded
 * rect in dash/gap segments, leaving the corners open. Caller sets lineStyle first.
 */
export function drawDashedRoundedRect(
  g: PixiGraphics,
  x: number,
  y: number,
  w: number,
  h: number,
  radius: number,
  dash: number,
  gap: number
): void {
  const step = dash + gap;
  if (step <= 0) return;
  const edges: Array<[number, number, number, number]> = [
    [x + radius, y, x + w - radius, y],
    [x + w, y + radius, x + w, y + h - radius],
    [x + w - radius, y + h, x + radius, y + h],
    [x, y + h - radius, x, y + radius],
  ];
  for (const [x0, y0, x1, y1] of edges) {
    const len = Math.hypot(x1 - x0, y1 - y0);
    if (len <= 0) continue;
    const ux = (x1 - x0) / len;
    const uy = (y1 - y0) / len;
    for (let d = 0; d < len; d += step) {
      const end = Math.min(d + dash, len);
      g.moveTo(x0 + ux * d, y0 + uy * d);
      g.lineTo(x0 + ux * end, y0 + uy * end);
    }
  }
}
