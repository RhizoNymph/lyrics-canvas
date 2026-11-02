import { describe, it, expect } from 'vitest';
import { meterLevel, rmsOf } from './level';

describe('rmsOf', () => {
  it('is 0 for silence and empty input', () => {
    expect(rmsOf(new Float32Array(128))).toBe(0);
    expect(rmsOf([])).toBe(0);
  });

  it('is the amplitude for a square wave and amplitude/sqrt(2) for a sine', () => {
    expect(rmsOf([0.5, -0.5, 0.5, -0.5])).toBeCloseTo(0.5);
    const sine = Array.from({ length: 1000 }, (_, i) => Math.sin((2 * Math.PI * i) / 100));
    expect(rmsOf(sine)).toBeCloseTo(Math.SQRT1_2, 3);
  });
});

describe('meterLevel', () => {
  it('maps -60 dBFS..0 dBFS onto 0..1 and clamps', () => {
    expect(meterLevel(0)).toBe(0);
    expect(meterLevel(0.0001)).toBe(0);
    expect(meterLevel(1)).toBe(1);
    expect(meterLevel(2)).toBe(1);
    expect(meterLevel(10 ** (-30 / 20))).toBeCloseTo(0.5);
    expect(meterLevel(Number.NaN)).toBe(0);
  });
});
