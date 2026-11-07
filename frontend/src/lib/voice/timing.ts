/** Playback position helpers for the check transcript view (pure, ms throughout). */
import type { TimedWord } from './types';

/** Alt+Left / Alt+Right seek step (dataset contract §4). */
export const SEEK_STEP_MS = 3000;

/**
 * The word being played at `timeMs`: the word with `start_ms <= t < end_ms`; when words overlap, the
 * one that started last. Null in gaps, outside the take and for non-finite times. `words` are in
 * service order (ascending `start_ms`), so this is a binary search for the last started word.
 */
export function wordIndexAt(words: readonly TimedWord[], timeMs: number): number | null {
  if (!Number.isFinite(timeMs) || words.length === 0) return null;
  let lo = 0;
  let hi = words.length - 1;
  let last = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (words[mid].start_ms <= timeMs) {
      last = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  // The latest started word that still covers t. Usually `last`; earlier only when words overlap.
  for (let i = last; i >= 0; i--) {
    if (timeMs < words[i].end_ms) return i;
  }
  return null;
}

/** Move `fromMs` by `deltaMs` within [0, durationMs]; a non-finite duration only clamps at 0. */
export function clampSeekMs(fromMs: number, deltaMs: number, durationMs: number): number {
  const from = Number.isFinite(fromMs) ? fromMs : 0;
  const to = Math.max(0, from + deltaMs);
  return Number.isFinite(durationMs) ? Math.min(to, Math.max(0, durationMs)) : to;
}
