import { describe, it, expect } from 'vitest';
import { viewportCenter } from './geometry';

describe('viewportCenter', () => {
  it('converts the screen centre to world coordinates', () => {
    expect(viewportCenter({ x: 0, y: 0, zoom: 1 }, { width: 800, height: 600 })).toEqual({ x: 400, y: 300 });
    expect(viewportCenter({ x: -100, y: 50, zoom: 2 }, { width: 800, height: 600 })).toEqual({ x: 300, y: 100 });
  });
});
