package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordIndexTest {
    private fun c(syllable: String, wordIndex: Int, word: String? = null) = GridCell(syllable = syllable, wordIndex = wordIndex, word = word)
    private fun rest() = GridCell(rest = true)

    @Test
    fun `renumbers by order of first appearance`() {
        assertEquals(listOf(0, 1, 1), rebaseWordIndexes(listOf(c("world", 1), c("hel", 0), c("lo", 0))).map { it.wordIndex })
    }

    @Test
    fun `keeps one index per word even when its syllables are interleaved`() {
        assertEquals(listOf(0, 1, 0), rebaseWordIndexes(listOf(c("hel", 0), c("world", 1), c("lo", 0))).map { it.wordIndex })
    }

    @Test
    fun `closes gaps left by removed words`() {
        assertEquals(listOf(0, 1, 2), rebaseWordIndexes(listOf(c("a", 0), c("c", 2), c("d", 5))).map { it.wordIndex })
    }

    @Test
    fun `skips rests and cells without a word_index, leaving them untouched`() {
        val out = rebaseWordIndexes(listOf(rest(), c("b", 3), GridCell(syllable = "x"), c("a", 1)))
        assertEquals(GridCell(rest = true), out[0])
        assertEquals(0, out[1].wordIndex)
        assertEquals(GridCell(syllable = "x"), out[2])
        assertEquals(1, out[3].wordIndex)
    }

    @Test
    fun `does not mutate its input`() {
        val input = listOf(c("b", 3), c("a", 1))
        rebaseWordIndexes(input)
        assertEquals(listOf(3, 1), input.map { it.wordIndex })
    }

    @Test
    fun `countSyllables counts non-rest cells`() {
        assertEquals(2, countSyllables(listOf(c("a", 0), rest(), c("b", 1), rest())))
        assertEquals(0, countSyllables(emptyList()))
    }

    @Test
    fun `attachWords fills word from raw_text by word_index`() {
        val line = GridLine(listOf(c("hel", 0), c("lo", 0), rest(), c("world,", 1)), 3, "hello  world,")
        assertEquals(listOf("hello", "hello", null, "world,"), attachWords(line).cells.map { it.word })
    }

    @Test
    fun `attachWords keeps an existing word and ignores out-of-range indexes`() {
        val line = GridLine(listOf(c("a", 0, "kept"), c("b", 7)), 2, "alpha")
        val out = attachWords(line)
        assertEquals("kept", out.cells[0].word)
        assertNull(out.cells[1].word)
    }

    @Test
    fun `attachWordsAll maps every line`() {
        val grid = GridData(listOf(GridLine(listOf(c("one", 0)), 1, "one"), GridLine(listOf(c("two", 0)), 1, "two")))
        assertEquals(listOf("one", "two"), attachWordsAll(grid).lines.map { it.cells[0].word })
    }
}
