package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.RhymeSources
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.RhymeTagFilter
import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.rhymeTagFilter
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.SnippetId

/** The lines pattern mode works on. */
data class PatternTarget(val canvasId: CanvasId, val snippetId: SnippetId, val range: PatternRange)

/** The pattern view's query controls (min score, sources, rare words), seeded from the single-mode controls. */
data class PatternParams(
    val minScore: Double = 0.5,
    val sources: RhymeSources = RhymeSources(),
    val includeRare: Boolean = false,
    /** The detection threshold (detect `min_score`). */
    val detectMinScore: Double = PatternLimits.DETECT_MIN_SCORE,
)

/** Which page a suggest request asks for. */
sealed interface PatternPage {
    /** Every on slot's first page, and the first page of line matches (when notes is on). */
    data object First : PatternPage

    /** The next page of one slot's list: the same slots, its offset bumped, no line matches. */
    data class Slot(val slot: SlotId, val offset: Int) : PatternPage

    /** The next page of line matches (slot lists trimmed to one row, which the caller ignores). */
    data class LineMatches(val offset: Int) : PatternPage
}

enum class PatternSkip {
    /** Every slot is off (or there are none): nothing to suggest. */
    NO_SLOTS_ON,

    /** Both sources are off. */
    NO_SOURCES,
}

sealed interface PatternPlan {
    data class Skip(val reason: PatternSkip) : PatternPlan

    /** "Only my lines matching" is known-invalid while notes is on (shown inline). */
    data class Blocked(val error: TagExprError) : PatternPlan

    data class Query(val request: PatternSuggestRequest) : PatternPlan
}

fun detectRequest(target: PatternTarget, params: PatternParams): PatternDetectRequest = PatternDetectRequest(
    canvasId = target.canvasId,
    snippetId = target.snippetId,
    lineStart = target.range.start,
    lineEnd = target.range.end,
    minScore = params.detectMinScore,
)

/**
 * The suggest request for [board]'s on slots (in slot order, ids kept, k left to the server).
 * Line matches are note lines, so they are asked for only while the notes source is on.
 */
fun planPatternSuggest(
    target: PatternTarget,
    board: SlotBoard,
    params: PatternParams,
    tagFilter: ExpressionField,
    dismissed: Set<String>,
    page: PatternPage = PatternPage.First,
): PatternPlan {
    val specs = board.specs()
    if (specs.isEmpty()) return PatternPlan.Skip(PatternSkip.NO_SLOTS_ON)
    val sources = params.sources.enabled()
    if (sources.isEmpty()) return PatternPlan.Skip(PatternSkip.NO_SOURCES)
    val filter = when (val f = rhymeTagFilter(tagFilter, params.sources.notes)) {
        is RhymeTagFilter.Blocked -> return PatternPlan.Blocked(f.error)
        is RhymeTagFilter.Send -> f.expression
        RhymeTagFilter.None -> null
    }
    val notes = params.sources.notes
    val request = PatternSuggestRequest(
        canvasId = target.canvasId,
        snippetId = target.snippetId,
        lineStart = target.range.start,
        lineEnd = target.range.end,
        slots = specs,
        minScore = params.minScore,
        sources = sources,
        includeRare = params.includeRare,
        tagFilter = filter,
        excludeIds = dismissed.sorted(),
        limit = if (page is PatternPage.LineMatches) 1 else PatternLimits.PAGE_SIZE,
        offsets = if (page is PatternPage.Slot) mapOf(page.slot.value to page.offset) else emptyMap(),
        lineMatches = notes && page !is PatternPage.Slot,
        lineMatchLimit = PatternLimits.PAGE_SIZE,
        lineMatchOffset = if (page is PatternPage.LineMatches) page.offset else 0,
    )
    return PatternPlan.Query(request)
}
