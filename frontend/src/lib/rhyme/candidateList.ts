/** Pure derivations for the sidebar's result list (rows, "Showing a–b of total", More). */
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate } from '@/types/rhyme';

/** Loaded candidates minus dismissed ids, limited to the pages loaded so far. */
export function listRows(
  candidates: ReadonlyArray<RhymeCandidate>,
  offset: number,
  dismissed: ReadonlySet<string>
): RhymeCandidate[] {
  return candidates.filter((c) => !dismissed.has(c.id)).slice(0, offset + RHYME_PAGE_SIZE);
}

export interface ShownRange {
  start: number;
  end: number;
}

/** 1-based "Showing start–end" bounds; 0–0 when the list is empty. */
export function shownRange(rowCount: number, _total: number): ShownRange {
  return rowCount === 0 ? { start: 0, end: 0 } : { start: 1, end: rowCount };
}

export function canLoadMore(loading: boolean, offset: number, total: number): boolean {
  return !loading && offset + RHYME_PAGE_SIZE < total;
}
