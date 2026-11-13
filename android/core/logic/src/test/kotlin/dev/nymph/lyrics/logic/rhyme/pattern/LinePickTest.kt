package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.lyric
import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinePickTest {
    private val song = lyric("a", listOf("first line here", "", "steady pressure on", "heavy weather wrong", "ready better long", ""))
    private val other = lyric("b", listOf("one", "two"))
    private val a = SnippetId("a")

    private fun range(start: Int, end: Int) = RangeCheck.Ok(PatternRange(start, end))

    @Test
    fun `pattern ranges need two lines with words inside the snippet`() {
        assertEquals(range(2, 5), patternRange(song, 2, 4))
        assertEquals(range(2, 5), patternRange(song, 4, 2))
        assertEquals(range(0, 3), patternRange(song, 0, 2))
        assertEquals(RangeCheck.Invalid(RangeProblem.TOO_FEW_LINES), patternRange(song, 0, 1))
        assertEquals(RangeCheck.Invalid(RangeProblem.TOO_FEW_LINES), patternRange(song, 3, 3))
        assertEquals(RangeCheck.Invalid(RangeProblem.OUTSIDE_SNIPPET), patternRange(song, 4, 6))
        assertEquals(RangeCheck.Invalid(RangeProblem.OUTSIDE_SNIPPET), patternRange(song, -1, 2))
        assertEquals(RangeCheck.Invalid(RangeProblem.OUTSIDE_SNIPPET), patternRange(snippet("x", "no grid"), 0, 1))
    }

    @Test
    fun `more than sixteen lines is too many`() {
        val long = lyric("l", (0 until 20).map { "line $it" })
        assertEquals(range(0, 16), patternRange(long, 0, 15))
        assertEquals(RangeCheck.Invalid(RangeProblem.TOO_MANY_LINES), patternRange(long, 0, 16))
    }

    @Test
    fun `last N lines end at the last line with words`() {
        assertEquals(range(2, 5), lastLinesRange(song, 3))
        assertEquals(range(1, 5), lastLinesRange(song, 4))
        // Clamped to 2..16 and to the snippet start.
        assertEquals(range(3, 5), lastLinesRange(song, 1))
        assertEquals(range(0, 5), lastLinesRange(song, 40))
        assertEquals(RangeCheck.Invalid(RangeProblem.TOO_FEW_LINES), lastLinesRange(lyric("e", listOf("", "")), 2))
    }

    @Test
    fun `the stepper defaults to min of four and the line count`() {
        assertEquals(4, defaultPatternLineCount(song))
        assertEquals(2, defaultPatternLineCount(other))
        assertEquals(2, defaultPatternLineCount(lyric("one", listOf("solo"))))
    }

    @Test
    fun `first tap then last tap picks the range`() {
        val first = pickLine(LinePick.AwaitingFirst(a), song, 4)
        assertEquals(LinePickStep.Next(LinePick.AwaitingLast(a, 4)), first)
        assertEquals(LinePickStep.Picked(a, PatternRange(2, 5)), pickLine(LinePick.AwaitingLast(a, 4), song, 2))
    }

    @Test
    fun `tapping the first line again clears it`() {
        assertEquals(LinePickStep.Next(LinePick.AwaitingFirst(a)), pickLine(LinePick.AwaitingLast(a, 3), song, 3))
    }

    @Test
    fun `an unusable range is rejected and keeps the first line`() {
        val pick = LinePick.AwaitingLast(a, 0)
        assertEquals(LinePickStep.Rejected(pick, RangeProblem.TOO_FEW_LINES), pickLine(pick, song, 1))
    }

    @Test
    fun `a tap on another snippet restarts there and off ignores taps`() {
        assertEquals(LinePickStep.Next(LinePick.AwaitingLast(other.id, 1)), pickLine(LinePick.AwaitingLast(a, 3), other, 1))
        assertEquals(LinePickStep.Next(LinePick.Off), pickLine(LinePick.Off, song, 2))
    }

    @Test
    fun `the Lines toggle starts and stops picking per chip`() {
        assertEquals(LinePick.AwaitingFirst(a), toggleLinePick(LinePick.Off, a))
        assertEquals(LinePick.Off, toggleLinePick(LinePick.AwaitingFirst(a), a))
        assertEquals(LinePick.Off, toggleLinePick(LinePick.AwaitingLast(a, 2), a))
        assertEquals(LinePick.AwaitingFirst(other.id), toggleLinePick(LinePick.AwaitingLast(a, 2), other.id))
        assertTrue(LinePick.AwaitingLast(a, 2).isFirst(a, 2))
        assertFalse(LinePick.AwaitingLast(a, 2).isFirst(a, 3))
        assertTrue(LinePick.AwaitingFirst(a).activeFor(a))
        assertFalse(LinePick.AwaitingFirst(a).activeFor(other.id))
    }
}
