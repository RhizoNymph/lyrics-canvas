package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.LineMatch
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.PatternSlot
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetType

/** One slot's list: the rows loaded so far, the server's total, and the offset of the last page. */
data class SlotColumn(
    val slot: PatternSlot,
    val candidates: List<PatternCandidate>,
    val total: Int,
    val offset: Int = 0,
) {
    val id: SlotId get() = SlotId(slot.id)

    val nextOffset: Int get() = offset + PatternLimits.PAGE_SIZE

    val canLoadMore: Boolean get() = nextOffset < total

    fun without(candidateId: String): SlotColumn = copy(candidates = candidates.filter { it.id != candidateId })
}

/** The whole-line matches loaded so far. */
data class LineMatchList(
    val matches: List<LineMatch> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
) {
    val nextOffset: Int get() = offset + PatternLimits.PAGE_SIZE

    val canLoadMore: Boolean get() = nextOffset < total

    fun without(unitId: String): LineMatchList = copy(matches = matches.filter { it.unit.id != unitId })
}

/** The first page of a suggest response as columns (in response order) and line matches, dismissed ids hidden. */
fun firstPage(response: PatternSuggestResponse, dismissed: Set<String>): Pair<List<SlotColumn>, LineMatchList> {
    val columns = response.slots.map { result -> SlotColumn(result.slot, result.candidates.filter { it.id !in dismissed }, result.total) }
    val lines = LineMatchList(response.lineMatches.filter { it.unit.id !in dismissed }, response.lineMatchTotal)
    return columns to lines
}

/** Append the [slot]'s page at [offset] from [response] to its column (rows already shown or dismissed are skipped). */
fun appendSlotPage(columns: List<SlotColumn>, slot: SlotId, offset: Int, response: PatternSuggestResponse, dismissed: Set<String>): List<SlotColumn> {
    val page = response.slots.firstOrNull { it.slot.id == slot.value } ?: return columns
    return columns.map { column ->
        if (column.id != slot) {
            column
        } else {
            val seen = column.candidates.mapTo(HashSet()) { it.id }
            val fresh = page.candidates.filter { it.id !in seen && it.id !in dismissed }
            column.copy(candidates = column.candidates + fresh, total = page.total, offset = offset)
        }
    }
}

fun appendLineMatchPage(list: LineMatchList, offset: Int, response: PatternSuggestResponse, dismissed: Set<String>): LineMatchList {
    val seen = list.matches.mapTo(HashSet()) { it.unit.id }
    val fresh = response.lineMatches.filter { it.unit.id !in seen && it.unit.id !in dismissed }
    return list.copy(matches = list.matches + fresh, total = response.lineMatchTotal, offset = offset)
}

/** The anchor id recorded on a materialized line match: the pattern's lines. */
fun patternAnchorId(target: PatternTarget): String = "pattern:${target.snippetId.value}:${target.range.start}-${target.range.end}"

/**
 * The create body for materializing a whole-line match next to the anchor, as a note candidate is
 * materialized: `derived_from` names the note line, its mean hit score, and the pattern lines as the
 * anchor; `k` and `match_depth` are the smallest k among the slots it hits.
 */
fun lineMatchRequest(match: LineMatch, target: PatternTarget, slots: List<PatternSlot>, anchorText: String, position: Point): SnippetCreateRequest {
    val hitIds = match.hits.mapTo(HashSet()) { it.slotId }
    val k = slots.filter { it.id in hitIds }.minOfOrNull { it.k.value } ?: 1
    return SnippetCreateRequest(
        positionX = position.x,
        positionY = position.y,
        snippetType = SnippetType.FREESTYLE,
        rawText = match.unit.text,
        derivedFrom = DerivedFrom(
            provenance = match.unit.provenance,
            sourceUnitId = match.unit.id,
            score = match.score,
            matchDepth = k,
            k = k,
            anchorUnitId = patternAnchorId(target),
            anchorText = anchorText,
        ),
    )
}
