package dev.nymph.lyrics.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CellSelectionTest {
    private fun sel(row: Int, start: Int, end: Int) = requireNotNull(CellSelection.of(row, start, end))

    @Test
    fun `of rejects empty, negative and reversed chunks`() {
        assertNull(CellSelection.of(0, 2, 2))
        assertNull(CellSelection.of(0, 3, 2))
        assertNull(CellSelection.of(0, -1, 1))
        assertNull(CellSelection.of(-1, 0, 1))
        assertEquals(2, sel(1, 2, 4).size)
    }

    @Test
    fun `contains and fitsIn`() {
        val s = sel(0, 1, 3)
        assertTrue(1 in s)
        assertTrue(2 in s)
        assertFalse(3 in s)
        assertTrue(s.fitsIn(3))
        assertFalse(s.fitsIn(2))
    }

    @Test
    fun `tap selects a single cell`() {
        assertEquals(sel(2, 4, 5), tapSelection(null, 2, 4, extend = false))
        assertEquals(sel(2, 4, 5), tapSelection(sel(2, 0, 2), 2, 4, extend = false))
    }

    @Test
    fun `tap with extend grows the selection in the same row only`() {
        assertEquals(sel(2, 1, 5), tapSelection(sel(2, 1, 2), 2, 4, extend = true))
        assertEquals(sel(2, 0, 3), tapSelection(sel(2, 2, 3), 2, 0, extend = true))
        assertEquals(sel(2, 1, 3), tapSelection(sel(2, 1, 3), 2, 2, extend = true))
        assertEquals(sel(1, 4, 5), tapSelection(sel(2, 1, 2), 1, 4, extend = true))
        assertEquals(sel(0, 3, 4), tapSelection(null, 0, 3, extend = true))
    }

    @Test
    fun `resize moves one edge and never crosses the other`() {
        val s = sel(0, 2, 4)
        assertEquals(sel(0, 0, 4), resizeSelection(s, SelectionEdge.START, 0, 6))
        assertEquals(sel(0, 3, 4), resizeSelection(s, SelectionEdge.START, 5, 6))
        assertEquals(sel(0, 2, 6), resizeSelection(s, SelectionEdge.END, 5, 6))
        assertEquals(sel(0, 2, 3), resizeSelection(s, SelectionEdge.END, 0, 6))
        assertEquals(sel(0, 2, 6), resizeSelection(s, SelectionEdge.END, 99, 6))
    }

    @Test
    fun `resize to the same bounds returns the same instance`() {
        val s = sel(0, 2, 4)
        assertSame(s, resizeSelection(s, SelectionEdge.START, 2, 6))
        assertSame(s, resizeSelection(s, SelectionEdge.END, 3, 6))
    }

    @Test
    fun `shiftedBy and inRow`() {
        assertEquals(sel(0, 3, 5), sel(0, 2, 4).shiftedBy(1))
        assertNull(sel(0, 0, 2).shiftedBy(-1))
        assertEquals(sel(3, 2, 4), sel(0, 2, 4).inRow(3))
    }

    @Test
    fun `indexAfterReorder matches reorderRows active-row rule`() {
        // move 1 -> 3 in [a b c d e]: [a c d b e]
        assertEquals(3, indexAfterReorder(1, 1, 3))
        assertEquals(1, indexAfterReorder(2, 1, 3))
        assertEquals(2, indexAfterReorder(3, 1, 3))
        assertEquals(0, indexAfterReorder(0, 1, 3))
        assertEquals(4, indexAfterReorder(4, 1, 3))
        // move 3 -> 1: [a d b c e]
        assertEquals(1, indexAfterReorder(3, 3, 1))
        assertEquals(2, indexAfterReorder(1, 3, 1))
        assertEquals(3, indexAfterReorder(2, 3, 1))
        assertEquals(4, indexAfterReorder(4, 3, 1))
    }
}
