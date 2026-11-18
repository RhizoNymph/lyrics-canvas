/**
 * The open editor's score as it follows grid edits (the rest link, contract §3.3-3.5):
 *
 * - a rest-only grid edit moves the notes from the current score (applyGridRestEdit) and returns the
 *   canonical grid; it becomes the new base;
 * - any other grid change (typing, a syllabify result, a chunk drag across syllables, a line move)
 *   reconciles from the base: the score and grid of the last stable point. Typing "hello" -> "hell" ->
 *   "hello" therefore gives the notes of "hello" back instead of losing them on the way;
 * - a staff edit or Make score makes the result the new base (`commitTrack`), with the rest link applied.
 */
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import { ScoreInvalid } from './errors';
import { jsonEqual } from './json';
import { applyGridRestEdit, applyRestLink, isRestOnlyEdit } from './restLink';
import { reconcile } from './reconcile';

export interface ScoreTrack {
  /** The score for the current grid; null when the snippet has none. */
  readonly score: Score | null;
  /** The last stable point reconcile starts from (null with no score). */
  readonly base: { readonly score: Score; readonly grid: GridData | null } | null;
}

export const NO_SCORE: ScoreTrack = { score: null, base: null };

export type FollowResult =
  | { readonly kind: 'unchanged' }
  /** The score follows; `grid` is the grid to show (canonical after a rest-only edit, else the new one). */
  | { readonly kind: 'followed'; readonly track: ScoreTrack; readonly grid: GridData | null }
  /** The edit would push a note past MAX_TICKS: keep the previous grid. */
  | { readonly kind: 'refused'; readonly error: ScoreInvalid };

/** A track whose base is `score` on `grid`, with the grid rest-linked. */
export function commitTrack(score: Score | null, grid: GridData | null): { track: ScoreTrack; grid: GridData | null } {
  if (score === null) return { track: NO_SCORE, grid };
  const linked = applyRestLink(score, grid);
  return { track: { score, base: { score, grid: linked } }, grid: linked };
}

export function followGrid(track: ScoreTrack, prevGrid: GridData | null, nextGrid: GridData | null): FollowResult {
  if (track.score === null || track.base === null || jsonEqual(prevGrid, nextGrid)) return { kind: 'unchanged' };
  try {
    if (isRestOnlyEdit(prevGrid, nextGrid)) {
      const moved = applyGridRestEdit(track.score, prevGrid, nextGrid);
      return { kind: 'followed', track: { score: moved.score, base: { score: moved.score, grid: moved.grid } }, grid: moved.grid };
    }
    const score = reconcile(track.base.score, track.base.grid, nextGrid);
    return { kind: 'followed', track: { score, base: track.base }, grid: nextGrid };
  } catch (error) {
    if (error instanceof ScoreInvalid) return { kind: 'refused', error };
    throw error;
  }
}
