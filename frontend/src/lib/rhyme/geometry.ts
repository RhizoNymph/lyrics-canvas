/** World-coordinate geometry shared by the rhyme store and its pure helpers (no Pixi imports). */

export interface Point {
  x: number;
  y: number;
}

export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export interface ViewSize {
  width: number;
  height: number;
}

/** The world point currently at the centre of the canvas element. */
export function viewportCenter(viewport: Viewport, viewSize: ViewSize): Point {
  return {
    x: viewSize.width / (2 * viewport.zoom) - viewport.x,
    y: viewSize.height / (2 * viewport.zoom) - viewport.y,
  };
}
