package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.logic.syls
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RestsTest {
    private fun c(syllable: String, wordIndex: Int) = GridCell(syllable = syllable, wordIndex = wordIndex)
    private val base = GridLine(listOf(c("hel", 0), c("lo", 0), c("world", 1)), 3, "hello world")

    @Test
    fun `insertRest inserts a rest before the given cell`() {
        assertEquals(listOf("hel", "lo", "·", "world"), syls(insertRest(base, 2)))
        assertEquals(listOf("·", "hel", "lo", "world"), syls(insertRest(base, 0)))
    }

    @Test
    fun `insertRest appends when at is past the end and clamps negatives`() {
        assertEquals(listOf("hel", "lo", "world", "·"), syls(insertRest(base, 10)))
        assertEquals(listOf("·", "hel", "lo", "world"), syls(insertRest(base, -3)))
    }

    @Test
    fun `insertRest keeps syllable_count and word indexes`() {
        val out = insertRest(base, 1)
        assertEquals(3, out.syllableCount)
        assertEquals(listOf(0, null, 0, 1), out.cells.map { it.wordIndex })
    }

    @Test
    fun `removeCell removes a rest without touching the count`() {
        val out = removeCell(insertRest(base, 1), 1)
        assertEquals(listOf("hel", "lo", "world"), syls(out))
        assertEquals(3, out.syllableCount)
    }

    @Test
    fun `removing a syllable recounts and renumbers words`() {
        val out = removeCell(base, 2)
        assertEquals(listOf("hel", "lo"), syls(out))
        assertEquals(2, out.syllableCount)
        val out2 = removeCell(removeCell(base, 0), 0)
        assertEquals(listOf(0), out2.cells.map { it.wordIndex })
    }

    @Test
    fun `drops the surface word from the remaining syllables of a trimmed word`() {
        val worded = base.copy(cells = base.cells.map { it.copy(word = if (it.wordIndex == 0) "hello" else "world") })
        assertEquals(listOf(null, "world"), removeCell(worded, 0).cells.map { it.word })
    }

    @Test
    fun `removeCell is a no-op out of range`() {
        assertSame(base, removeCell(base, 3))
        assertSame(base, removeCell(base, -1))
    }
}
