import { describe, it, expect } from 'vitest';
import { snippet } from '@/lib/merge/fixtures';
import {
  alsoOnText,
  canvasBadge,
  isOnOtherCanvases,
  otherCanvases,
  renameCanvasRef,
  sortCanvasRefs,
  withCanvasRef,
} from './canvases';

const a = { id: 'a', title: 'Alpha' };
const b = { id: 'b', title: 'beta' };
const c = { id: 'c', title: 'Gamma' };

describe('sortCanvasRefs', () => {
  it('sorts by lower(title), then id', () => {
    expect(sortCanvasRefs([c, b, a]).map((r) => r.id)).toEqual(['a', 'b', 'c']);
    const twins = [{ id: 'z', title: 'same' }, { id: 'y', title: 'Same' }];
    expect(sortCanvasRefs(twins).map((r) => r.id)).toEqual(['y', 'z']);
  });

  it('does not mutate its input', () => {
    const refs = [c, a];
    sortCanvasRefs(refs);
    expect(refs).toEqual([c, a]);
  });
});

describe('withCanvasRef', () => {
  it('adds a missing canvas in sorted position', () => {
    expect(withCanvasRef([a, c], b)).toEqual([a, b, c]);
  });

  it('leaves the list as is when the canvas is already there with that title', () => {
    const refs = [a, b];
    expect(withCanvasRef(refs, { id: 'b', title: 'beta' })).toBe(refs);
  });

  it('refreshes the title of a canvas already there', () => {
    expect(withCanvasRef([a, b], { id: 'b', title: 'Zeta' })).toEqual([a, { id: 'b', title: 'Zeta' }]);
  });
});

describe('renameCanvasRef', () => {
  it('renames and re-sorts', () => {
    expect(renameCanvasRef([a, b, c], 'a', 'zulu')).toEqual([b, c, { id: 'a', title: 'zulu' }]);
  });

  it('returns the same array when the canvas is absent or unchanged', () => {
    const refs = [a, b];
    expect(renameCanvasRef(refs, 'x', 'whatever')).toBe(refs);
    expect(renameCanvasRef(refs, 'a', 'Alpha')).toBe(refs);
  });
});

describe('otherCanvases / isOnOtherCanvases', () => {
  it('lists every canvas except the current one', () => {
    const s = snippet('s', 'x', { canvases: [a, b, c] });
    expect(otherCanvases(s, 'b')).toEqual([a, c]);
    expect(isOnOtherCanvases(s, 'b')).toBe(true);
  });

  it('a snippet only on this canvas has no others', () => {
    const s = snippet('s', 'x', { canvases: [a] });
    expect(otherCanvases(s, 'a')).toEqual([]);
    expect(isOnOtherCanvases(s, 'a')).toBe(false);
  });
});

describe('canvasBadge', () => {
  it('shows "⧉ N" only with more than one canvas', () => {
    expect(canvasBadge(snippet('s', 'x', { canvases: [a] }))).toBeNull();
    expect(canvasBadge(snippet('s', 'x', { canvases: [] }))).toBeNull();
    expect(canvasBadge(snippet('s', 'x', { canvases: [a, b] }))).toEqual({ count: 2, label: '⧉ 2' });
    expect(canvasBadge(snippet('s', 'x', { canvases: [a, b, c] }))?.label).toBe('⧉ 3');
  });
});

describe('alsoOnText', () => {
  it('names the other canvases', () => {
    expect(alsoOnText(snippet('s', 'x', { canvases: [a, b, c] }), 'a')).toBe('Also on: beta, Gamma');
  });

  it('is null when the snippet is only here', () => {
    expect(alsoOnText(snippet('s', 'x', { canvases: [a] }), 'a')).toBeNull();
  });
});
