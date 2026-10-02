package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RawTextTest {
    private fun c(syllable: String, wordIndex: Int, word: String? = null) = GridCell(syllable = syllable, wordIndex = wordIndex, word = word)
    private fun rest() = GridCell(rest = true)
    private fun line(cells: List<GridCell>, rawText: String = "stale") = GridLine(cells, 0, rawText)

    @Test
    fun `joins words in order of first appearance, concatenating syllables`() {
        assertEquals("world hello", rebuildRawText(line(listOf(c("world", 0), c("hel", 1), c("lo", 1)))))
    }

    @Test
    fun `prefers the surface word when present (API splits are approximate)`() {
        val w = "hullabaloo,"
        assertEquals(
            "hullabaloo, yo",
            rebuildRawText(line(listOf(c("hul", 0, w), c("lab", 0, w), c("alo", 0, w), c("o", 0, w), c("yo", 1, "yo")))),
        )
    }

    @Test
    fun `skips rests and keeps a word together when its syllables are interleaved`() {
        assertEquals("hello world", rebuildRawText(line(listOf(c("hel", 0), rest(), c("world", 1), c("lo", 0), rest()))))
    }

    @Test
    fun `treats cells without a word_index as their own words`() {
        assertEquals("a b c", rebuildRawText(line(listOf(GridCell(syllable = "a"), c("b", 0), GridCell(syllable = "c")))))
    }

    @Test
    fun `drops words that have no text`() {
        assertEquals("x", rebuildRawText(line(listOf(c("", 0), c("x", 1)))))
        assertEquals("", rebuildRawText(line(listOf(rest(), rest()))))
    }

    @Test
    fun `withRebuiltText writes the text into raw_text`() {
        assertEquals("b a", withRebuiltText(line(listOf(c("b", 1), c("a", 0)))).rawText)
    }

    @Test
    fun `rebuildRawTextAll joins lines with newlines`() {
        val grid = GridData(listOf(line(listOf(c("one", 0))), line(listOf(c("two", 0), c("three", 1)))))
        assertEquals("one\ntwo three", rebuildRawTextAll(grid))
    }
}
