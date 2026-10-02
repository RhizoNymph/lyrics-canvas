package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeLimits
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Slot
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SourceName

/** Where the query's slot comes from: typed by hand, or the first anchor snippet's line / range. */
enum class SlotMode { MANUAL, FROM_SELECTION }

data class RhymeSources(val notes: Boolean = true, val dictionary: Boolean = true) {
    /** Enabled sources in request order (notes before dictionary). */
    fun enabled(): List<SourceName> = buildList {
        if (notes) add(SourceName.NOTES)
        if (dictionary) add(SourceName.DICTIONARY)
    }

    fun toggle(name: SourceName): RhymeSources = when (name) {
        SourceName.NOTES -> copy(notes = !notes)
        SourceName.DICTIONARY -> copy(dictionary = !dictionary)
    }
}

/** The sheet's query controls; defaults match the web store's. */
data class RhymeQueryParams(
    val k: RhymeK = RhymeK.K2,
    val minScore: Double = 0.5,
    /** The manual slot; used only when [slotMode] is [SlotMode.MANUAL]. */
    val slot: Slot? = null,
    val slotMode: SlotMode = SlotMode.FROM_SELECTION,
    val sources: RhymeSources = RhymeSources(),
    val includeRare: Boolean = false,
)

/** What the canvas and the sheet contribute to a query. */
data class RhymeSelection(
    val selectedIds: Set<SnippetId>,
    val snippets: Map<SnippetId, Snippet>,
    val typedText: String = "",
    /** Chips removed for this query. */
    val excluded: Set<SnippetId> = emptySet(),
    /** Word ranges picked on chips. */
    val ranges: Map<SnippetId, AnchorRange> = emptyMap(),
    /** Candidate ids dismissed this session. */
    val dismissed: Set<String> = emptySet(),
)

/**
 * The outcome of planning a query. Either way it carries the pruned exclusions and ranges, which
 * the caller stores back (the web store prunes on every query).
 */
sealed interface RhymeQueryPlan {
    val excluded: Set<SnippetId>
    val ranges: Map<SnippetId, AnchorRange>

    /** Nothing to ask (no anchors or no sources): clear the results without a request. */
    data class Skip(
        override val excluded: Set<SnippetId>,
        override val ranges: Map<SnippetId, AnchorRange>,
    ) : RhymeQueryPlan

    data class Query(
        val request: RhymeSuggestRequest,
        /** The slot sent, for display. */
        val slot: Slot?,
        override val excluded: Set<SnippetId>,
        override val ranges: Map<SnippetId, AnchorRange>,
    ) : RhymeQueryPlan
}

/** Drop exclusions for snippets no longer selected ("for this query" semantics). */
fun pruneExclusions(excluded: Set<SnippetId>, selectedIds: Set<SnippetId>): Set<SnippetId> =
    excluded.filterTo(LinkedHashSet()) { it in selectedIds }

/** Drop ranges for snippets no longer selected, and ranges the snippet's current text can no longer satisfy. */
fun pruneRanges(
    ranges: Map<SnippetId, AnchorRange>,
    selectedIds: Set<SnippetId>,
    snippets: Map<SnippetId, Snippet>,
): Map<SnippetId, AnchorRange> = ranges.filter { (id, range) ->
    id in selectedIds && snippets[id]?.let { rangeFits(it, range) } == true
}

/** Slot "from selection": the first anchor snippet's picked word range, else its whole last line. */
fun slotFromFirstAnchor(anchorSnips: List<Snippet>, ranges: Map<SnippetId, AnchorRange>): Slot? {
    val first = anchorSnips.firstOrNull() ?: return null
    val range = ranges[first.id]
    return if (range == null) slotFromSnippet(first, null) else slotFromRange(first, range)
}

/** Build the rhyme-suggest request for one page ([offset]) of the current sheet state. */
fun planRhymeQuery(canvasId: CanvasId, params: RhymeQueryParams, selection: RhymeSelection, offset: Int): RhymeQueryPlan {
    val excluded = pruneExclusions(selection.excluded, selection.selectedIds)
    val ranges = pruneRanges(selection.ranges, selection.selectedIds, selection.snippets)
    val anchors = anchorsFromSelection(selection.selectedIds, selection.snippets, selection.typedText, excluded, ranges)
    val sources = params.sources.enabled()
    if (anchors.isEmpty() || sources.isEmpty()) return RhymeQueryPlan.Skip(excluded, ranges)

    val anchorSnips = anchorSnippets(selection.selectedIds, selection.snippets, excluded)
    val slot = when (params.slotMode) {
        SlotMode.FROM_SELECTION -> slotFromFirstAnchor(anchorSnips, ranges)
        SlotMode.MANUAL -> params.slot
    }
    val request = RhymeSuggestRequest(
        canvasId = canvasId,
        anchors = anchors,
        context = emptyList(),
        slot = slot,
        k = params.k,
        minScore = params.minScore,
        sources = sources,
        includeRare = params.includeRare,
        excludeIds = selection.dismissed.toList(),
        limit = RhymeLimits.PAGE_SIZE,
        offset = offset,
        weights = null,
        dialect = null,
    )
    return RhymeQueryPlan.Query(request, slot, excluded, ranges)
}

/** The `derived_from` recorded on a materialized snippet. */
fun derivedFromFor(candidate: RhymeCandidate, anchors: List<RhymeUnit>, k: RhymeK): DerivedFrom = DerivedFrom(
    provenance = candidate.provenance,
    sourceUnitId = candidate.id,
    score = candidate.score,
    matchDepth = candidate.matchDepth,
    k = k.value,
    anchorUnitId = candidate.bestAnchorId,
    anchorText = anchors.firstOrNull { it.id == candidate.bestAnchorId }?.text ?: "",
)

/** The create body for materializing [candidate] at [position] (see [materializePosition]). */
fun materializeRequest(candidate: RhymeCandidate, anchors: List<RhymeUnit>, k: RhymeK, position: Point): SnippetCreateRequest =
    SnippetCreateRequest(
        positionX = position.x,
        positionY = position.y,
        snippetType = SnippetType.FREESTYLE,
        rawText = candidate.text,
        derivedFrom = derivedFromFor(candidate, anchors, k),
    )
