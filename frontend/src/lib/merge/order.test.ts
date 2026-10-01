import { describe, it, expect } from 'vitest';
import { orderByPosition } from './order';
import { snippet } from './fixtures';

const a = snippet('a', 'a', { position_x: 100, position_y: 300 });
const b = snippet('b', 'b', { position_x: 300, position_y: 100 });
const c = snippet('c', 'c', { position_x: 200, position_y: 100 });
const d = snippet('d', 'd', { position_x: 100, position_y: 100 });

describe('orderByPosition', () => {
  it('vertical: top-to-bottom by y, then left-to-right by x', () => {
    expect(orderByPosition([a, b, c, d], 'vertical').map((s) => s.id)).toEqual(['d', 'c', 'b', 'a']);
  });

  it('horizontal: left-to-right by x, then top-to-bottom by y', () => {
    expect(orderByPosition([a, b, c, d], 'horizontal').map((s) => s.id)).toEqual(['d', 'a', 'c', 'b']);
  });

  it('breaks exact ties by id and does not mutate the input', () => {
    const e = snippet('e', 'e', { position_x: 100, position_y: 100 });
    const input = [e, d];
    expect(orderByPosition(input, 'vertical').map((s) => s.id)).toEqual(['d', 'e']);
    expect(input.map((s) => s.id)).toEqual(['e', 'd']);
  });
});
