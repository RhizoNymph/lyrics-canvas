/**
 * Rhyme "Fit rhythm" (round-4 contract §6): when on and the query anchors on a snippet, the server keeps
 * only candidates with the slot's syllable count and ranks them inside rhyme bands by how well their
 * stress fits the slot's rhythm on the snippet's staff. The client sends the flag, reads back whether it
 * was applied (and why not), shows each row's `rhythm_fit` as a percent, and remembers the switch per
 * viewer. See docs/features/web_score_tools.md ("Fit rhythm").
 */
import type { KeyValueStorage } from '@/lib/listMode/viewMode';
import { presentationOf } from './order';
import type { OrderPresentation, ResultOrdering } from './order';
import type { AnchorRef, RhythmUnavailableReason } from '@/types/rhyme';

/** True when the query has a snippet anchor: rhythm fit means nothing for typed text alone. */
export function rhythmEligible(anchors: readonly AnchorRef[]): boolean {
  return anchors.some((a) => a.kind === 'snippet');
}

/**
 * The request field: `{rhythm_fit: true}` only when the switch is on and a snippet is an anchor; left
 * out otherwise (the server default), so requests without it are byte-identical to the older ones.
 */
export function rhythmFitField(on: boolean, anchors: readonly AnchorRef[]): { rhythm_fit?: true } {
  return on && rhythmEligible(anchors) ? { rhythm_fit: true } : {};
}

/** Why a requested rhythm fit was not applied; `unknown` = the server gave no reason (older server). */
export type RhythmUnavailable = RhythmUnavailableReason | 'unknown';

/** Whether a result set asked for rhythm fit, whether the server applied it, and why not. */
export interface RhythmFitting {
  readonly requested: boolean;
  readonly applied: boolean;
  /** Set exactly when requested and not applied. */
  readonly unavailable: RhythmUnavailable | null;
}

export const RHYTHM_OFF: RhythmFitting = { requested: false, applied: false, unavailable: null };

/** The response fields this module reads. */
export interface RhythmAnswer {
  readonly rhythm_applied?: boolean;
  readonly rhythm_unavailable_reason?: RhythmUnavailableReason | null;
}

export function rhythmFitting(requested: boolean, answer: RhythmAnswer): RhythmFitting {
  if (!requested) return RHYTHM_OFF;
  if (answer.rhythm_applied === true) return { requested: true, applied: true, unavailable: null };
  const reason = answer.rhythm_unavailable_reason;
  return {
    requested: true,
    applied: false,
    unavailable: reason === 'no_score' || reason === 'slot_unplaced' ? reason : 'unknown',
  };
}

const UNAVAILABLE_NOTES: Readonly<Record<RhythmUnavailable, string>> = {
  no_score: 'Fit rhythm unavailable: the anchor snippet has no score',
  slot_unplaced: 'Fit rhythm unavailable: some slot syllables are not placed on the staff',
  unknown: 'Fit rhythm unavailable',
};

/** The quiet note under the list when rhythm fit was asked for but not applied; null otherwise. */
export function rhythmNote(f: RhythmFitting): string | null {
  return f.unavailable === null ? null : UNAVAILABLE_NOTES[f.unavailable];
}

/** `rhythm_fit` 0..1 as a whole percent, or null when the row has none. */
export function rhythmPercent(fit: number | null | undefined): number | null {
  if (fit === null || fit === undefined || !Number.isFinite(fit)) return null;
  return Math.round(Math.max(0, Math.min(1, fit)) * 100);
}

/**
 * How the sidebar list draws its rows: the server bands candidates whenever rhythm fit was applied (in
 * either order), so headers show then; otherwise the order decides as before.
 */
export function listPresentation(ordering: ResultOrdering, rhythm: RhythmFitting): OrderPresentation {
  if (rhythm.applied) return 'bands';
  return presentationOf(ordering);
}

// ---- Per-viewer persistence ----

export const RHYTHM_FIT_STORAGE_KEY = 'lyrics-canvas.rhymeRhythmFit';

export type RhythmFitStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };
export type SaveRhythmFitResult = { ok: true } | { ok: false; error: RhythmFitStorageError };

export function parseRhythmFit(raw: string | null): boolean | null {
  if (raw === 'on') return true;
  if (raw === 'off') return false;
  return null;
}

export function loadRhythmFit(storage: KeyValueStorage | null): boolean {
  if (storage === null) return false;
  let raw: string | null;
  try {
    raw = storage.getItem(RHYTHM_FIT_STORAGE_KEY);
  } catch (error) {
    console.warn('rhythm fit: read failed, using off', { key: RHYTHM_FIT_STORAGE_KEY, error });
    return false;
  }
  return parseRhythmFit(raw) ?? false;
}

export function saveRhythmFit(storage: KeyValueStorage | null, on: boolean): SaveRhythmFitResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(RHYTHM_FIT_STORAGE_KEY, on ? 'on' : 'off');
    return { ok: true };
  } catch (cause) {
    console.warn('rhythm fit: write failed', { key: RHYTHM_FIT_STORAGE_KEY, on, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
