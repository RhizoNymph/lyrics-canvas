import { describe, it, expect } from 'vitest';
import { stableHash, layoutCandidates, anchorCenter, DEFAULT_LAYOUT } from './radialLayout';
import type { LayoutInput, Point } from './radialLayout';
import type { Snippet } from '@/types';

function cand(id: string, key: string[], score: number): LayoutInput {
  return { id, rhyme_key: key, score };
}

const center: Point = { x: 100, y: 50 };

describe('stableHash', () => {
  it('is deterministic and non-negative', () => {
    expect(stableHash('AO R IH N JH')).toBe(stableHash('AO R IH N JH'));
    expect(stableHash('abc')).toBeGreaterThanOrEqual(0);
    expect(Number.isInteger(stableHash('abc'))).toBe(true);
  });

  it('differs for different strings', () => {
    expect(stableHash('a')).not.toBe(stableHash('b'));
    expect(stableHash('')).not.toBe(stableHash(' '));
  });
});

describe('layoutCandidates', () => {
  it('places distance = base + (1 - score) * spread (within jitter) from the centre', () => {
    const out = layoutCandidates([cand('dict:a', ['IH', 'N'], 1.0), cand('dict:b', ['IH', 'N'], 0.5)], center, new Map());
    const a = out.get('dict:a')!;
    const b = out.get('dict:b')!;
    const da = Math.hypot(a.x - center.x, a.y - center.y);
    const db = Math.hypot(b.x - center.x, b.y - center.y);
    const { base, spread, distanceJitter } = DEFAULT_LAYOUT;
    expect(Math.abs(da - base)).toBeLessThanOrEqual(distanceJitter + 1e-6);
    expect(Math.abs(db - (base + 0.5 * spread))).toBeLessThanOrEqual(distanceJitter + 1e-6);
    expect(db).toBeGreaterThan(da);
  });

  it('puts the same rhyme family in the same wedge and different families apart', () => {
    const fam = ['dict:x', 'dict:y', 'dict:z'].map((id) => cand(id, ['AO', 'R', 'IH', 'N', 'JH'], 0.8));
    const other = cand('dict:q', ['EY', 'T'], 0.8);
    const out = layoutCandidates([...fam, other], center, new Map());
    const angle = (p: Point) => Math.atan2(p.y - center.y, p.x - center.x);
    const angles = fam.map((c) => angle(out.get(c.id)!));
    const wrap = (d: number) => Math.abs(Math.atan2(Math.sin(d), Math.cos(d)));
    for (const a of angles) {
      expect(wrap(a - angles[0])).toBeLessThanOrEqual(2 * DEFAULT_LAYOUT.angleJitter + 1e-6);
    }
    // Members of one family do not sit on the same point.
    expect(out.get('dict:x')).not.toEqual(out.get('dict:y'));
    const famHash = stableHash('AO R IH N JH') % 360;
    const otherHash = stableHash('EY T') % 360;
    expect(famHash).not.toBe(otherHash);
  });

  it('keeps existing ids at their previous position', () => {
    const existing = new Map<string, Point>([['dict:a', { x: -999, y: 42 }]]);
    const out = layoutCandidates([cand('dict:a', ['IH', 'N'], 0.2), cand('dict:b', ['IH', 'N'], 0.9)], center, existing);
    expect(out.get('dict:a')).toEqual({ x: -999, y: 42 });
    expect(out.has('dict:b')).toBe(true);
    // does not mutate the input map
    expect(existing.size).toBe(1);
  });

  it('is deterministic across calls', () => {
    const inputs = [cand('dict:a', ['IH', 'N'], 0.7), cand('note:s:0:1', ['IH', 'N'], 0.6)];
    const a = layoutCandidates(inputs, center, new Map());
    const b = layoutCandidates(inputs, center, new Map());
    expect(Array.from(a.entries())).toEqual(Array.from(b.entries()));
  });

  it('never produces NaN, even for bad scores or empty keys', () => {
    const out = layoutCandidates(
      [cand('a', [], Number.NaN), cand('b', [], Number.POSITIVE_INFINITY), cand('c', ['X'], -3), cand('d', ['X'], 7)],
      center,
      new Map()
    );
    for (const p of out.values()) {
      expect(Number.isFinite(p.x)).toBe(true);
      expect(Number.isFinite(p.y)).toBe(true);
    }
    // out-of-range scores are clamped to [0, 1]
    const dc = Math.hypot(out.get('c')!.x - center.x, out.get('c')!.y - center.y);
    const dd = Math.hypot(out.get('d')!.x - center.x, out.get('d')!.y - center.y);
    expect(dc).toBeGreaterThan(dd);
  });

  it('returns an empty map for no candidates', () => {
    expect(layoutCandidates([], center, new Map()).size).toBe(0);
  });
});

describe('anchorCenter', () => {
  const snip = (id: string, x: number, y: number, w = 100, h = 50): Snippet =>
    ({ id, position_x: x, position_y: y, width: w, height: h } as unknown as Snippet);

  it('returns the centroid of snippet centres', () => {
    const c = anchorCenter([snip('a', 0, 0), snip('b', 100, 100)], { x: 5, y: 5 });
    expect(c).toEqual({ x: 100, y: 75 });
  });

  it('falls back when there are no snippets', () => {
    expect(anchorCenter([], { x: 5, y: 6 })).toEqual({ x: 5, y: 6 });
  });
});
