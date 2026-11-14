/**
 * Rhyme result order: "Rhyme" (score order, the server default) or "Rhyme, then meaning" (fixed rhyme-score
 * bands in rhyme order, sorted by embedding similarity to the anchor inside each band). The server sorts and
 * sends each row's `band`; the constants here are the one client copy of the band edges (labels and a
 * fallback when a row has no band). The chosen order is remembered per viewer in browser storage.
 * See docs/features/web_rhyme_patterns.md ("Order").
 */
import type { KeyValueStorage } from '@/lib/listMode/viewMode';

export type RhymeOrder = 'rhyme' | 'meaning';
export type RhymeBand = 'perfect' | 'strong' | 'near' | 'loose';

export const RHYME_ORDERS: readonly RhymeOrder[] = ['rhyme', 'meaning'];
export const DEFAULT_RHYME_ORDER: RhymeOrder = 'rhyme';
export const RHYME_ORDER_LABELS: Readonly<Record<RhymeOrder, string>> = {
  rhyme: 'Rhyme',
  meaning: 'Rhyme, then meaning',
};

/** Bands in rhyme order (best first). */
export const RHYME_BANDS: readonly RhymeBand[] = ['perfect', 'strong', 'near', 'loose'];
/** Lower score edge (inclusive) of each band; `loose` takes everything below `near`. */
export const BAND_MIN_SCORE: Readonly<Record<Exclude<RhymeBand, 'loose'>, number>> = {
  perfect: 0.95,
  strong: 0.85,
  near: 0.75,
};
export const BAND_LABELS: Readonly<Record<RhymeBand, string>> = {
  perfect: 'Perfect',
  strong: 'Strong',
  near: 'Near',
  loose: 'Loose',
};

export function bandOf(score: number): RhymeBand {
  if (score >= BAND_MIN_SCORE.perfect) return 'perfect';
  if (score >= BAND_MIN_SCORE.strong) return 'strong';
  if (score >= BAND_MIN_SCORE.near) return 'near';
  return 'loose';
}

/** A row's band: the server's when sent, else derived from its score with the same edges. */
export function rowBand(row: { readonly band?: RhymeBand; readonly score: number }): RhymeBand {
  return row.band ?? bandOf(row.score);
}

/**
 * The request field for an order. `rhyme` is the server default and is left out, so a rhyme-order request
 * is byte-identical to the one sent before the toggle existed.
 */
export function orderField(order: RhymeOrder): { order?: 'meaning' } {
  return order === 'meaning' ? { order: 'meaning' } : {};
}

/**
 * How a result list presents its order:
 * - `plain`: rhyme order was asked and given; no headers.
 * - `bands`: meaning order was applied; band headers where the band changes.
 * - `unavailable`: meaning was asked but the server answered in rhyme order (model unavailable): a quiet note, no headers.
 */
export type OrderPresentation = 'plain' | 'bands' | 'unavailable';

/** `applied` is the response's `order_applied`; absent (older server) reads as rhyme. */
export function orderPresentation(requested: RhymeOrder, applied: RhymeOrder | undefined): OrderPresentation {
  if ((applied ?? 'rhyme') === 'meaning') return 'bands';
  return requested === 'meaning' ? 'unavailable' : 'plain';
}

export const MEANING_UNAVAILABLE_NOTE = 'Meaning order unavailable';

/** One entry of a banded list: a header before the first row of each band run, then the rows. */
export type BandedItem<T> =
  | { readonly kind: 'header'; readonly band: RhymeBand; readonly key: string }
  | { readonly kind: 'row'; readonly row: T };

/**
 * Rows interleaved with band headers where the band changes (`bands` only; otherwise just the rows).
 * Appended pages continue the current band: no header repeats while the band stays the same.
 */
export function withBandHeaders<T extends { readonly band?: RhymeBand; readonly score: number }>(
  rows: readonly T[],
  presentation: OrderPresentation,
): BandedItem<T>[] {
  const out: BandedItem<T>[] = [];
  let current: RhymeBand | null = null;
  let run = 0;
  for (const row of rows) {
    if (presentation === 'bands') {
      const band = rowBand(row);
      if (band !== current) {
        out.push({ kind: 'header', band, key: `band:${band}:${run}` });
        current = band;
        run += 1;
      }
    }
    out.push({ kind: 'row', row });
  }
  return out;
}

/** Similarity 0..1 as a whole percent, or null when there is none. */
export function similarityPercent(similarity: number | null | undefined): number | null {
  if (similarity === null || similarity === undefined || !Number.isFinite(similarity)) return null;
  return Math.round(Math.max(0, Math.min(1, similarity)) * 100);
}

/** The order a result set was asked for and the order the server applied. */
export interface ResultOrdering {
  readonly requested: RhymeOrder;
  readonly applied: RhymeOrder;
}

export const RHYME_ORDERING: ResultOrdering = { requested: 'rhyme', applied: 'rhyme' };

export function resultOrdering(requested: RhymeOrder, applied: RhymeOrder | undefined): ResultOrdering {
  return { requested, applied: applied ?? 'rhyme' };
}

export function presentationOf(ordering: ResultOrdering): OrderPresentation {
  return orderPresentation(ordering.requested, ordering.applied);
}

// ---- Per-viewer persistence ----

export const RHYME_ORDER_STORAGE_KEY = 'lyrics-canvas.rhymeOrder';

export type RhymeOrderStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };
export type SaveRhymeOrderResult = { ok: true } | { ok: false; error: RhymeOrderStorageError };

export function parseRhymeOrder(raw: string | null): RhymeOrder | null {
  return raw === 'rhyme' || raw === 'meaning' ? raw : null;
}

export function loadRhymeOrder(storage: KeyValueStorage | null): RhymeOrder {
  if (storage === null) return DEFAULT_RHYME_ORDER;
  let raw: string | null;
  try {
    raw = storage.getItem(RHYME_ORDER_STORAGE_KEY);
  } catch (error) {
    console.warn('rhyme order: read failed, using default', { key: RHYME_ORDER_STORAGE_KEY, error });
    return DEFAULT_RHYME_ORDER;
  }
  return parseRhymeOrder(raw) ?? DEFAULT_RHYME_ORDER;
}

export function saveRhymeOrder(storage: KeyValueStorage | null, order: RhymeOrder): SaveRhymeOrderResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(RHYME_ORDER_STORAGE_KEY, order);
    return { ok: true };
  } catch (cause) {
    console.warn('rhyme order: write failed', { key: RHYME_ORDER_STORAGE_KEY, order, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
