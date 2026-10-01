import { describe, it, expect } from 'vitest';
import { materializePosition, MATERIALIZE_GAP_X, MATERIALIZE_STEP_Y } from './materializePosition';
import type { AnchorRect } from './materializePosition';

const anchor: AnchorRect = { id: 'a', position_x: 100, position_y: 200, width: 250 };
const other: AnchorRect = { id: 'b', position_x: 900, position_y: 900, width: 150 };
const viewport = { x: -100, y: 50, zoom: 2 };
const viewSize = { width: 800, height: 600 };
// world centre = size / (2 * zoom) - viewport offset
const centre = { x: 800 / 4 + 100, y: 600 / 4 - 50 };

describe('materializePosition', () => {
  it('places the first materialization to the right of a single anchor on the same row', () => {
    const p = materializePosition({ anchors: [anchor], counts: new Map(), viewport, viewSize });
    expect(p).toEqual({ x: 100 + 250 + MATERIALIZE_GAP_X, y: 200 });
  });

  it('steps down by the row step for each prior materialization from the same anchor', () => {
    const p = materializePosition({ anchors: [anchor], counts: new Map([['a', 2]]), viewport, viewSize });
    expect(p).toEqual({ x: 100 + 250 + MATERIALIZE_GAP_X, y: 200 + 2 * MATERIALIZE_STEP_Y });
  });

  it('ignores counts recorded for other anchors', () => {
    const p = materializePosition({ anchors: [anchor], counts: new Map([['b', 5]]), viewport, viewSize });
    expect(p.y).toBe(200);
  });

  it('falls back to the viewport centre in world coordinates with no anchor snippet', () => {
    expect(materializePosition({ anchors: [], counts: new Map(), viewport, viewSize })).toEqual(centre);
  });

  it('falls back to the viewport centre with more than one anchor snippet', () => {
    expect(materializePosition({ anchors: [anchor, other], counts: new Map(), viewport, viewSize })).toEqual(centre);
  });

  it('exports the documented spacing constants', () => {
    expect(MATERIALIZE_GAP_X).toBe(40);
    expect(MATERIALIZE_STEP_Y).toBe(60);
  });
});
