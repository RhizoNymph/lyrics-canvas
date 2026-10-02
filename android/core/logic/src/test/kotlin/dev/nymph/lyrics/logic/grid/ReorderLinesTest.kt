package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReorderLinesTest {
    private fun l(t: String) = GridLine(listOf(GridCell(syllable = t, wordIndex = 0)), 1, t)
    private val grid = GridData(listOf(l("a"), l("b"), l("c"), l("d")))
    private fun texts(g: GridData) = g.lines.map { it.rawText }

    @Test
    fun `moves a line down`() {
        assertEquals(listOf("b", "a", "c", "d"), texts(reorderLines(grid, 0, 1)))
        assertEquals(listOf("b", "c", "d", "a"), texts(reorderLines(grid, 0, 3)))
    }

    @Test
    fun `moves a line up`() {
        assertEquals(listOf("d", "a", "b", "c"), texts(reorderLines(grid, 3, 0)))
        assertEquals(listOf("a", "c", "b", "d"), texts(reorderLines(grid, 2, 1)))
    }

    @Test
    fun `is a no-op for same or out-of-range indexes`() {
        assertSame(grid, reorderLines(grid, 1, 1))
        assertSame(grid, reorderLines(grid, 4, 0))
        assertSame(grid, reorderLines(grid, 0, -1))
    }

    @Test
    fun `does not mutate the input`() {
        reorderLines(grid, 0, 3)
        assertEquals(listOf("a", "b", "c", "d"), texts(grid))
    }
}
