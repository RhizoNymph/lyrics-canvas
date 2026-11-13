package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.lastNonEmptyLineIndex
import dev.nymph.lyrics.logic.rhyme.lineWordCount
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.Snippet

/**
 * A consecutive line range `[start, end)` of one snippet that pattern mode can use: inside the
 * snippet, at most [PatternLimits.MAX_LINES] lines, at least [PatternLimits.MIN_LINES] of them
 * with words. Built only by [patternRange] / [lastLinesRange].
 */
@ConsistentCopyVisibility
data class PatternRange internal constructor(val start: Int, val end: Int) {
    val size: Int get() = end - start

    operator fun contains(lineIndex: Int): Boolean = lineIndex in start until end
}

enum class RangeProblem {
    /** The snippet has no beat grid, or the range leaves it. */
    OUTSIDE_SNIPPET,

    /** Fewer than two lines with words. */
    TOO_FEW_LINES,

    /** More than [PatternLimits.MAX_LINES] lines. */
    TOO_MANY_LINES,
}

sealed interface RangeCheck {
    data class Ok(val range: PatternRange) : RangeCheck

    data class Invalid(val problem: RangeProblem) : RangeCheck
}

/** The snippet's line count (beat-grid lines). */
fun snippetLineCount(snippet: Snippet): Int = snippet.gridData?.lines?.size ?: 0

/** The lines `first..last` (inclusive, either order) of [snippet] as a pattern range. */
fun patternRange(snippet: Snippet, first: Int, last: Int): RangeCheck {
    val lines = snippet.gridData?.lines ?: return RangeCheck.Invalid(RangeProblem.OUTSIDE_SNIPPET)
    val start = minOf(first, last)
    val end = maxOf(first, last) + 1
    if (start < 0 || end > lines.size) return RangeCheck.Invalid(RangeProblem.OUTSIDE_SNIPPET)
    if (end - start > PatternLimits.MAX_LINES) return RangeCheck.Invalid(RangeProblem.TOO_MANY_LINES)
    val withWords = (start until end).count { lineWordCount(lines[it]) > 0 }
    if (withWords < PatternLimits.MIN_LINES) return RangeCheck.Invalid(RangeProblem.TOO_FEW_LINES)
    return RangeCheck.Ok(PatternRange(start, end))
}

/** The "Pattern (last N lines)" stepper's default: min(4, line count), at least 2. */
fun defaultPatternLineCount(snippet: Snippet): Int =
    minOf(4, snippetLineCount(snippet)).coerceIn(PatternLimits.MIN_LINES, PatternLimits.MAX_LINES)

/**
 * The last [count] lines (clamped to 2..16) ending at the snippet's last non-empty line; fewer when
 * the snippet is shorter.
 */
fun lastLinesRange(snippet: Snippet, count: Int): RangeCheck {
    val last = lastNonEmptyLineIndex(snippet) ?: return RangeCheck.Invalid(RangeProblem.TOO_FEW_LINES)
    val n = count.coerceIn(PatternLimits.MIN_LINES, PatternLimits.MAX_LINES)
    return patternRange(snippet, maxOf(0, last - n + 1), last)
}
