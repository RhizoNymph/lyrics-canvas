/**
 * Where a materialized candidate lands on the canvas.
 *
 * With exactly one anchor snippet: to its right on the same row, stepping down one row per
 * earlier materialization from that anchor so the new snippets do not stack. Otherwise (typed
 * anchor only, or several anchor snippets): the current viewport centre in world coordinates.
 */
import type { Snippet } from '@/types';
import { viewportCenter } from './geometry';
import type { Point, Viewport, ViewSize } from './geometry';

/** Horizontal gap between the anchor's right edge and the new snippet. */
export const MATERIALIZE_GAP_X = 40;
/** Vertical step per earlier materialization from the same anchor. */
export const MATERIALIZE_STEP_Y = 60;

export type AnchorRect = Pick<Snippet, 'id' | 'position_x' | 'position_y' | 'width'>;

export interface MaterializeInput {
  /** The snippets currently acting as anchors (selected, non-blank, not excluded). */
  anchors: ReadonlyArray<AnchorRect>;
  /** Materializations already made per anchor snippet id this session. */
  counts: ReadonlyMap<string, number>;
  viewport: Viewport;
  viewSize: ViewSize;
}

export function materializePosition({ anchors, counts, viewport, viewSize }: MaterializeInput): Point {
  if (anchors.length !== 1) return viewportCenter(viewport, viewSize);
  const anchor = anchors[0];
  const prior = counts.get(anchor.id) ?? 0;
  return {
    x: anchor.position_x + anchor.width + MATERIALIZE_GAP_X,
    y: anchor.position_y + prior * MATERIALIZE_STEP_Y,
  };
}
