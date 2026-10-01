import { describe, it, expect } from 'vitest';
import { gapFromX, moveTargetFromGap } from './dropIndex';

describe('gapFromX', () => {
  it('snaps to the nearest cell boundary', () => {
    expect(gapFromX(0, 60, 4)).toBe(0);
    expect(gapFromX(29, 60, 4)).toBe(0);
    expect(gapFromX(31, 60, 4)).toBe(1);
    expect(gapFromX(150, 60, 4)).toBe(3); // 2.5 rounds to 3
  });

  it('clamps to [0, cellCount]', () => {
    expect(gapFromX(-50, 60, 4)).toBe(0);
    expect(gapFromX(1000, 60, 4)).toBe(4);
  });

  it('handles a zero or negative cell width without NaN', () => {
    expect(gapFromX(10, 0, 4)).toBe(0);
  });
});

describe('moveTargetFromGap', () => {
  // chunk is cells [2,4) of 6 cells
  it('maps a gap before the chunk directly', () => {
    expect(moveTargetFromGap(0, 2, 4)).toBe(0);
    expect(moveTargetFromGap(1, 2, 4)).toBe(1);
  });

  it('maps a gap after the chunk into removed-chunk coordinates', () => {
    expect(moveTargetFromGap(5, 2, 4)).toBe(3);
    expect(moveTargetFromGap(6, 2, 4)).toBe(4);
  });

  it('returns null when the drop would not move the chunk', () => {
    expect(moveTargetFromGap(2, 2, 4)).toBeNull();
    expect(moveTargetFromGap(3, 2, 4)).toBeNull();
    expect(moveTargetFromGap(4, 2, 4)).toBeNull();
  });
});
