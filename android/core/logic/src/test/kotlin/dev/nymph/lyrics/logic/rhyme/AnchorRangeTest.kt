package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnchorRangeTest {
    private data class C(val w: Int? = null, val rest: Boolean = false, val s: Int = 0)

    private fun line(rawText: String, cells: List<C>) = GridLine(
        cells.map { if (it.rest) GridCell(rest = true) else GridCell(syllable = "x", stress = it.s, wordIndex = it.w) },
        cells.count { !it.rest },
        rawText,
    )

    // "orange door hinge": or-ange (w0 w0) · door (w1) hinge (w2) ·
    private val hingeLine = line(
        "orange door hinge",
        listOf(C(0, s = 1), C(0), C(rest = true), C(1, s = 1), C(2, s = 1), C(rest = true)),
    )
    private val snip = snippet(
        "a",
        "I saw a black cat\norange door hinge",
        GridData(listOf(line("I saw a black cat", listOf(C(0), C(1, s = 1), C(2), C(3, s = 1), C(4, s = 1))), hingeLine)),
    )

    private fun awaiting(l: Int, s: Int, e: Int) = RangeClickState.AwaitingEnd(AnchorRange(l, s, e))
    private fun complete(l: Int, s: Int, e: Int) = RangeClickState.Complete(AnchorRange(l, s, e))

    @Test
    fun `wordSpan orders the clicks and stores the end exclusively`() {
        assertEquals(AnchorRange(1, 2, 5), wordSpan(1, 2, 4))
        assertEquals(AnchorRange(1, 2, 5), wordSpan(1, 4, 2))
        assertEquals(AnchorRange(0, 3, 4), wordSpan(0, 3, 3))
    }

    @Test
    fun `first click starts a single-word range and waits for the end`() {
        assertEquals(awaiting(1, 1, 2), clickWord(RangeClickState.None, 1, 1))
    }

    @Test
    fun `second click on the same line closes the range (inclusive click, exclusive end), in either order`() {
        val started = clickWord(RangeClickState.None, 1, 1)
        assertEquals(complete(1, 1, 3), clickWord(started, 1, 2))
        val startedLate = clickWord(RangeClickState.None, 1, 2)
        assertEquals(complete(1, 0, 3), clickWord(startedLate, 1, 0))
    }

    @Test
    fun `clicking the start word again clears, whether or not the range is complete`() {
        val started = clickWord(RangeClickState.None, 1, 1)
        assertEquals(RangeClickState.None, clickWord(started, 1, 1))
        val complete = clickWord(started, 1, 2)
        assertEquals(RangeClickState.None, clickWord(complete, 1, 1))
    }

    @Test
    fun `a click on another word after a complete range starts over from that word`() {
        val complete = clickWord(clickWord(RangeClickState.None, 1, 0), 1, 1)
        assertEquals(awaiting(1, 2, 3), clickWord(complete, 1, 2))
    }

    @Test
    fun `a click on a different line starts over on that line`() {
        val started = clickWord(RangeClickState.None, 1, 1)
        assertEquals(awaiting(0, 3, 4), clickWord(started, 0, 3))
        val complete = clickWord(started, 1, 2)
        assertEquals(awaiting(0, 4, 5), clickWord(complete, 0, 4))
    }

    @Test
    fun `never mutates its input`() {
        val started = clickWord(RangeClickState.None, 1, 1)
        val copy = awaiting(1, 1, 2)
        clickWord(started, 1, 2)
        assertEquals(copy, started)
        assertNull(RangeClickState.None.range)
        assertFalse(RangeClickState.None.awaitingEnd)
    }

    @Test
    fun `sliceGridLine keeps the cells whose word index is in range, re-based, dropping leading and trailing rests`() {
        val out = sliceGridLine(hingeLine, AnchorRange(1, 1, 3))
        assertEquals(listOf(0, 1), out.cells.map { it.wordIndex })
        assertEquals(2, out.syllableCount)
        assertEquals("door hinge", out.rawText)
    }

    @Test
    fun `sliceGridLine keeps interior rests`() {
        val out = sliceGridLine(hingeLine, AnchorRange(1, 0, 2))
        assertEquals(listOf<Any?>(0, 0, "rest", 1), out.cells.map { if (it.isRest) "rest" else it.wordIndex })
        assertEquals(3, out.syllableCount)
        assertEquals("orange door", out.rawText)
    }

    @Test
    fun `counts whitespace-separated words of the line text`() {
        assertEquals(3, lineWordCount(hingeLine))
        assertEquals(2, lineWordCount(line("  two   words ", emptyList())))
        assertEquals(0, lineWordCount(line("", emptyList())))
    }

    @Test
    fun `rangeText is the selected words of the snippet line`() {
        assertEquals("black cat", rangeText(snip, AnchorRange(0, 3, 5)))
        assertEquals("door", rangeText(snip, AnchorRange(1, 1, 2)))
        assertNull(rangeText(snip, AnchorRange(5, 0, 1)))
    }

    @Test
    fun `rangeFits checks the line exists and the range lies inside its words`() {
        assertTrue(rangeFits(snip, AnchorRange(0, 0, 5)))
        assertTrue(rangeFits(snip, AnchorRange(1, 2, 3)))
        assertFalse(rangeFits(snip, AnchorRange(1, 2, 4)))
        assertFalse(rangeFits(snip, AnchorRange(1, 2, 2)))
        assertFalse(rangeFits(snip, AnchorRange(2, 0, 1)))
        assertFalse(rangeFits(snippet("x", "x", null), AnchorRange(0, 0, 1)))
    }

    @Test
    fun `anchorRefFor is the whole last line without a range and the word range with one`() {
        assertEquals(SnippetAnchorRef(SnippetId("a"), lineIndex = null), anchorRefFor(snip, null))
        assertEquals(SnippetAnchorRef(SnippetId("a"), 1, 1, 3), anchorRefFor(snip, AnchorRange(1, 1, 3)))
    }
}
