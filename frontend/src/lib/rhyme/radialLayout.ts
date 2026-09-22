/**
 * Deterministic radial layout for rhyme candidates around an anchor centre.
 *
 *   distance = base + (1 - score) * spread          (closer = better rhyme)
 *   angle    = hash(rhyme_key.join(' ')) mod 2π     (a rhyme family forms a wedge)
 *   + small per-id jitter on both so family members do not overlap.
 *
 * Existing ids keep their position across re-queries; only new ids are placed.
 * All positions are world coordinates of the card centre.
 */
import type { Snippet } from '@/types';

export interface Point {
  x: number;
  y: number;
}

export interface LayoutInput {
  id: string;
  rhyme_key: string[];
  score: number;
}

export interface LayoutOptions {
  /** World-unit distance for a perfect (score 1) rhyme. */
  base: number;
  /** Extra distance added for score 0. */
  spread: number;
  /** Max absolute per-id angle jitter in radians. */
  angleJitter: number;
  /** Max absolute per-id distance jitter in world units. */
  distanceJitter: number;
}

export const DEFAULT_LAYOUT: LayoutOptions = {
  base: 320,
  spread: 480,
  angleJitter: 0.22,
  distanceJitter: 70,
};

const TWO_PI = Math.PI * 2;

/** FNV-1a 32-bit hash; deterministic, non-negative integer. */
export function stableHash(str: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** Map a hash to [-1, 1). */
function unit(h: number): number {
  return (h % 10007) / 10007 * 2 - 1;
}

function clampScore(score: number): number {
  if (!Number.isFinite(score)) return 0;
  return Math.max(0, Math.min(1, score));
}

export function placeCandidate(c: LayoutInput, center: Point, opts: LayoutOptions = DEFAULT_LAYOUT): Point {
  const familyAngle = (stableHash(c.rhyme_key.join(' ')) % 360) * (TWO_PI / 360);
  const angle = familyAngle + unit(stableHash(`${c.id}|a`)) * opts.angleJitter;
  const distance = opts.base + (1 - clampScore(c.score)) * opts.spread + unit(stableHash(`${c.id}|d`)) * opts.distanceJitter;
  return { x: center.x + Math.cos(angle) * distance, y: center.y + Math.sin(angle) * distance };
}

/**
 * Returns a new map containing every entry of `existing` plus fresh positions for ids
 * in `candidates` that are not yet placed. Never mutates `existing`.
 */
export function layoutCandidates(
  candidates: ReadonlyArray<LayoutInput>,
  center: Point,
  existing: ReadonlyMap<string, Point>,
  opts: LayoutOptions = DEFAULT_LAYOUT
): Map<string, Point> {
  const out = new Map<string, Point>(existing);
  for (const c of candidates) {
    if (out.has(c.id)) continue;
    out.set(c.id, placeCandidate(c, center, opts));
  }
  return out;
}

/** Centroid of the snippets' centres, or `fallback` (viewport centre) when there are none. */
export function anchorCenter(snippets: ReadonlyArray<Snippet>, fallback: Point): Point {
  if (snippets.length === 0) return fallback;
  let x = 0;
  let y = 0;
  for (const s of snippets) {
    x += s.position_x + s.width / 2;
    y += s.position_y + s.height / 2;
  }
  return { x: x / snippets.length, y: y / snippets.length };
}
