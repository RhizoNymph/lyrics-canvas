/** Provenance for a whole-line match materialized from the pattern view (as a note candidate's is). */
import type { DerivedFrom } from '@/types/rhyme';
import type { LineMatch, PatternSlot, SpanRef } from '@/types/rhymePattern';

/** The pattern member id format of the contract: `pattern:<snippet_id>:<line>:<start>-<end>`. */
export function patternSpanId(snippetId: string, span: SpanRef): string {
  return `pattern:${snippetId}:${span.line_index}:${span.word_start}-${span.word_end}`;
}

/**
 * The line match's first hit slot stands in for the anchor: its last member (the line nearest where the
 * user writes next) is the anchor unit, and its `k` is both `k` and `match_depth`.
 */
export function lineMatchDerivedFrom(match: LineMatch, slots: ReadonlyMap<string, PatternSlot>, snippetId: string): DerivedFrom {
  const first = match.hits[0];
  const slot = first === undefined ? undefined : slots.get(first.slot_id);
  const anchor = slot === undefined ? undefined : slot.members[slot.members.length - 1];
  return {
    provenance: match.unit.provenance,
    source_unit_id: match.unit.id,
    score: match.score,
    match_depth: slot?.k ?? 1,
    k: slot?.k ?? 1,
    anchor_unit_id: anchor === undefined ? `pattern:${snippetId}` : patternSpanId(snippetId, anchor),
    anchor_text: anchor?.text ?? '',
  };
}
