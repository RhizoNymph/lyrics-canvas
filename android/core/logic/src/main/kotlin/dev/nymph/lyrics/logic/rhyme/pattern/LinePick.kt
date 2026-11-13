package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/**
 * The chip expansion's "Lines" mode: tap the first line, then the last line of a range. Only one
 * snippet picks at a time.
 */
sealed interface LinePick {
    data object Off : LinePick

    data class AwaitingFirst(val snippetId: SnippetId) : LinePick

    data class AwaitingLast(val snippetId: SnippetId, val first: Int) : LinePick
}

/** The outcome of one line tap. */
sealed interface LinePickStep {
    /** Keep picking with this state. */
    data class Next(val pick: LinePick) : LinePickStep

    /** A valid range of 2+ lines: enter pattern mode for this snippet. */
    data class Picked(val snippetId: SnippetId, val range: PatternRange) : LinePickStep

    /** The range is unusable; the first line stays picked so another last line can be tapped. */
    data class Rejected(val pick: LinePick, val problem: RangeProblem) : LinePickStep
}

/** The "Lines" toggle on a chip: on starts picking there, off (or the same chip again) stops. */
fun toggleLinePick(pick: LinePick, snippetId: SnippetId): LinePick = when {
    pick is LinePick.AwaitingFirst && pick.snippetId == snippetId -> LinePick.Off
    pick is LinePick.AwaitingLast && pick.snippetId == snippetId -> LinePick.Off
    else -> LinePick.AwaitingFirst(snippetId)
}

/**
 * A line tapped on [snippet]'s expanded chip:
 * - picking off: ignored;
 * - another snippet's line: picking moves to that snippet with this as its first line;
 * - first line: remembered;
 * - the first line again: cleared (await a new first line);
 * - another line: the range first..line, picked when valid.
 */
fun pickLine(pick: LinePick, snippet: Snippet, lineIndex: Int): LinePickStep = when (pick) {
    LinePick.Off -> LinePickStep.Next(LinePick.Off)
    is LinePick.AwaitingFirst -> LinePickStep.Next(LinePick.AwaitingLast(snippet.id, lineIndex))
    is LinePick.AwaitingLast -> when {
        pick.snippetId != snippet.id -> LinePickStep.Next(LinePick.AwaitingLast(snippet.id, lineIndex))
        pick.first == lineIndex -> LinePickStep.Next(LinePick.AwaitingFirst(snippet.id))
        else -> when (val check = patternRange(snippet, pick.first, lineIndex)) {
            is RangeCheck.Ok -> LinePickStep.Picked(snippet.id, check.range)
            is RangeCheck.Invalid -> LinePickStep.Rejected(pick, check.problem)
        }
    }
}

/** True when [lineIndex] of [snippetId] is the picked first line (for highlighting). */
fun LinePick.isFirst(snippetId: SnippetId, lineIndex: Int): Boolean =
    this is LinePick.AwaitingLast && this.snippetId == snippetId && first == lineIndex

fun LinePick.activeFor(snippetId: SnippetId): Boolean = when (this) {
    LinePick.Off -> false
    is LinePick.AwaitingFirst -> this.snippetId == snippetId
    is LinePick.AwaitingLast -> this.snippetId == snippetId
}
