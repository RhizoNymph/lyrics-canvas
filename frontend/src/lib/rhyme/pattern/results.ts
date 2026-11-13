/**
 * The suggest results as the view keeps them: one column per slot (independent paging through
 * `offsets`), and the whole-line matches with their own paging. Pure merges of server pages.
 */
import { PATTERN_LINE_MATCH_PAGE_SIZE, PATTERN_PAGE_SIZE } from '@/types/rhymePattern';
import type { LineMatch, PatternCandidate, PatternSlot, PatternSuggestResponse } from '@/types/rhymePattern';
import { lineWords } from '@/lib/grid/restMarks';

export interface SlotColumn {
  /** The slot as the server recomputed it from the members sent. */
  readonly slot: PatternSlot;
  readonly candidates: readonly PatternCandidate[];
  readonly total: number;
  /** Offset of the last page fetched. */
  readonly offset: number;
}

export interface PatternResults {
  readonly columns: ReadonlyMap<string, SlotColumn>;
  readonly lineMatches: readonly LineMatch[];
  readonly lineMatchTotal: number;
  readonly lineMatchOffset: number;
}

export function firstPageResults(res: PatternSuggestResponse, dismissed: ReadonlySet<string>): PatternResults {
  return {
    columns: new Map(
      res.slots.map((r) => [r.slot.id, { slot: r.slot, candidates: r.candidates.filter((c) => !dismissed.has(c.id)), total: r.total, offset: 0 }]),
    ),
    lineMatches: res.line_matches.filter((m) => !dismissed.has(m.unit.id)),
    lineMatchTotal: res.line_match_total,
    lineMatchOffset: 0,
  };
}

/** Merge one slot's next page (fetched with `offsets[slotId] = offset`); the other slots are left alone. */
export function appendSlotPage(
  results: PatternResults,
  slotId: string,
  offset: number,
  res: PatternSuggestResponse,
  dismissed: ReadonlySet<string>,
): PatternResults {
  const column = results.columns.get(slotId);
  const page = res.slots.find((r) => r.slot.id === slotId);
  if (column === undefined || page === undefined) return results;
  const seen = new Set(column.candidates.map((c) => c.id));
  const fresh = page.candidates.filter((c) => !seen.has(c.id) && !dismissed.has(c.id));
  const columns = new Map(results.columns);
  columns.set(slotId, { slot: page.slot, candidates: [...column.candidates, ...fresh], total: page.total, offset });
  return { ...results, columns };
}

export function appendLineMatches(
  results: PatternResults,
  offset: number,
  res: PatternSuggestResponse,
  dismissed: ReadonlySet<string>,
): PatternResults {
  const seen = new Set(results.lineMatches.map((m) => m.unit.id));
  const fresh = res.line_matches.filter((m) => !seen.has(m.unit.id) && !dismissed.has(m.unit.id));
  return { ...results, lineMatches: [...results.lineMatches, ...fresh], lineMatchTotal: res.line_match_total, lineMatchOffset: offset };
}

export function dismissFromResults(results: PatternResults, id: string): PatternResults {
  const columns = new Map<string, SlotColumn>();
  for (const [slotId, column] of results.columns) {
    columns.set(slotId, column.candidates.some((c) => c.id === id) ? { ...column, candidates: column.candidates.filter((c) => c.id !== id) } : column);
  }
  return { ...results, columns, lineMatches: results.lineMatches.filter((m) => m.unit.id !== id) };
}

export function canLoadMoreSlot(column: SlotColumn): boolean {
  return column.offset + PATTERN_PAGE_SIZE < column.total;
}

export function canLoadMoreLines(results: PatternResults): boolean {
  return results.lineMatchOffset + PATTERN_LINE_MATCH_PAGE_SIZE < results.lineMatchTotal;
}

/** The latest server view of each slot (for the legend's rhyme key). */
export function serverSlotsOf(results: PatternResults): Map<string, PatternSlot> {
  return new Map([...results.columns].map(([id, c]) => [id, c.slot]));
}

export interface MatchWord {
  readonly text: string;
  /** Slot order index of the hit covering the word, or null. */
  readonly slotIndex: number | null;
}

/** The line's display words with the hit words marked; hits of slots not shown (`slotIndex` null) are plain. */
export function lineMatchWords(match: LineMatch, slotIndex: (slotId: string) => number | null): MatchWord[] {
  return lineWords(match.unit.text).map((text, i) => {
    const hit = match.hits.find((h) => i >= h.word_start && i < h.word_end);
    return { text, slotIndex: hit === undefined ? null : slotIndex(hit.slot_id) };
  });
}

export function slotsHitLabel(match: LineMatch, slotCount: number): string {
  return `${match.slots_hit} of ${slotCount} slots`;
}
