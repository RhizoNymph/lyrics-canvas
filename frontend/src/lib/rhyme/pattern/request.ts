/** Building the detect and suggest requests from the pattern session and the edit state (contract §1.2). */
import { PATTERN_LINE_MATCH_PAGE_SIZE, PATTERN_PAGE_SIZE } from '@/types/rhymePattern';
import type { PatternDetectRequest, PatternLines, PatternSuggestRequest, SlotSpec } from '@/types/rhymePattern';
import type { SourceName } from '@/types/rhyme';
import type { PatternSession, SlotEditState } from './types';

/** The shared rhyme controls, read from rhymeStore, plus the session's dismissed ids. */
export interface PatternParams {
  readonly minScore: number;
  readonly sources: readonly SourceName[];
  readonly includeRare: boolean;
  /** Normalized tag expression, or null for no restriction (an invalid one never reaches here). */
  readonly tagFilter: string | null;
  readonly excludeIds: readonly string[];
}

/** Which page a suggest call fetches: the whole first page, one slot's next page, or more line matches. */
export type SuggestPage =
  | { readonly kind: 'first' }
  | { readonly kind: 'slot_more'; readonly slotId: string; readonly offset: number }
  | { readonly kind: 'line_more'; readonly offset: number };

function linesOf(session: PatternSession): PatternLines {
  return {
    canvas_id: session.canvasId,
    snippet_id: session.snippetId,
    line_start: session.lineStart,
    line_end: session.lineEnd,
    weights: null,
    dialect: null,
  };
}

export function buildDetectRequest(session: PatternSession, minScore: number): PatternDetectRequest {
  return { ...linesOf(session), min_score: minScore };
}

/** The on slots in slot order; members are already in line order (an edit-state invariant). */
export function slotSpecs(edit: SlotEditState): SlotSpec[] {
  return edit.slots
    .filter((s) => s.on)
    .map((s) => ({
      id: s.id,
      k: null,
      members: s.members.map((m) => ({ line_index: m.line, word_start: m.start, word_end: m.end })),
    }));
}

/** Null when there is nothing to ask: no on slot, or every source off. */
export function buildSuggestRequest(
  session: PatternSession,
  edit: SlotEditState,
  params: PatternParams,
  page: SuggestPage,
): PatternSuggestRequest | null {
  const slots = slotSpecs(edit);
  if (slots.length === 0 || params.sources.length === 0) return null;
  return {
    ...linesOf(session),
    slots,
    min_score: params.minScore,
    sources: [...params.sources],
    include_rare: params.includeRare,
    ...(params.tagFilter !== null ? { tag_filter: params.tagFilter } : {}),
    exclude_ids: [...params.excludeIds],
    limit: PATTERN_PAGE_SIZE,
    offsets: page.kind === 'slot_more' ? { [page.slotId]: page.offset } : {},
    line_matches: page.kind !== 'slot_more',
    line_match_limit: PATTERN_LINE_MATCH_PAGE_SIZE,
    line_match_offset: page.kind === 'line_more' ? page.offset : 0,
  };
}
