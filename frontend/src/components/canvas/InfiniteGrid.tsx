import { useCallback } from 'react';
import { Graphics } from '@pixi/react';
import type { Graphics as PixiGraphics } from 'pixi.js';

interface Props {
  viewport: { x: number; y: number; zoom: number };
  width: number;
  height: number;
}

export default function InfiniteGrid({ viewport, width, height }: Props) {
  const draw = useCallback(
    (g: PixiGraphics) => {
      g.clear();
      const gridSize = 50 * viewport.zoom;
      const offsetX = (viewport.x * viewport.zoom) % gridSize;
      const offsetY = (viewport.y * viewport.zoom) % gridSize;

      g.lineStyle(1, 0x2a2a4a, 0.3);

      for (let x = offsetX; x < width; x += gridSize) {
        g.moveTo(x, 0);
        g.lineTo(x, height);
      }
      for (let y = offsetY; y < height; y += gridSize) {
        g.moveTo(0, y);
        g.lineTo(width, y);
      }
    },
    [viewport.x, viewport.y, viewport.zoom, width, height]
  );

  return <Graphics draw={draw} />;
}
