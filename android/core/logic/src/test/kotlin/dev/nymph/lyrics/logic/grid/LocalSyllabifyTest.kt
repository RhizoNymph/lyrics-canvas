package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalSyllabifyTest {
    @Test
    fun `estimateSyllableCount counts vowel groups with the silent-e adjustment`() {
        assertEquals(4, estimateSyllableCount("hullabaloo"))
        assertEquals(1, estimateSyllableCount("make"))
        assertEquals(1, estimateSyllableCount("rhythm"))
        assertEquals(1, estimateSyllableCount("123"))
    }

    @Test
    fun `splitWord splits into estimated chunks`() {
        assertEquals(listOf("hul", "lab", "alo", "o"), splitWord("hullabaloo"))
        assertEquals(listOf("cat"), splitWord("cat"))
    }

    @Test
    fun `builds a grid line with word indexes, surface words and raw_text`() {
        val line = localSyllabifyLine("  hello   world, ")
        assertEquals("hello   world,", line.rawText)
        assertEquals(listOf("hel", "lo", "world,"), line.cells.map { it.syllable })
        assertEquals(listOf(0, 0, 1), line.cells.map { it.wordIndex })
        assertEquals(listOf("hello", "hello", "world,"), line.cells.map { it.word })
        assertEquals(3, line.syllableCount)
    }

    @Test
    fun `returns an empty line for blank text`() {
        assertEquals(GridLine(emptyList(), 0, ""), localSyllabifyLine("   "))
    }
}
