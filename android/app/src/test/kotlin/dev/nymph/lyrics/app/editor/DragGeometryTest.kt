package dev.nymph.lyrics.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DragGeometryTest {
    // 5 cells, 100 px wide, starting 20 px into the strip content, 80 px tall.
    private val m = CellMetrics(originX = 20f, cellWidth = 100f, cellHeight = 80f)

    @Test
    fun `metrics reject a non-positive cell size`() {
        assertThrows<IllegalArgumentException> { CellMetrics(0f, 0f, 10f) }
        assertThrows<IllegalArgumentException> { CellMetrics(0f, 10f, -1f) }
    }

    @Test
    fun `cellIndexAt maps content x to the cell under it`() {
        assertEquals(0, cellIndexAt(20f, m, 5))
        assertEquals(0, cellIndexAt(119f, m, 5))
        assertEquals(1, cellIndexAt(120f, m, 5))
        assertEquals(4, cellIndexAt(519f, m, 5))
        assertNull(cellIndexAt(520f, m, 5))
        assertNull(cellIndexAt(19f, m, 5))
        assertNull(cellIndexAt(50f, m, 0))
    }

    @Test
    fun `cellIndexClamped keeps a handle inside the row`() {
        assertEquals(0, cellIndexClamped(-300f, m, 5))
        assertEquals(2, cellIndexClamped(250f, m, 5))
        assertEquals(4, cellIndexClamped(9000f, m, 5))
    }

    @Test
    fun `dropGapAt picks the nearest gap via gapFromX`() {
        assertEquals(0, dropGapAt(20f, m, 5))
        assertEquals(0, dropGapAt(69f, m, 5))
        assertEquals(1, dropGapAt(70f, m, 5)) // half a cell rounds up, as JS Math.round
        assertEquals(3, dropGapAt(330f, m, 5))
        assertEquals(5, dropGapAt(10_000f, m, 5))
        assertEquals(0, dropGapAt(-10_000f, m, 5))
    }

    @Test
    fun `drop gap from a gesture combines with moveTargetFromGap`() {
        // chunk [1, 3) dragged to just past cell 4 (gap 5) lands at index 3 of the remaining cells
        val gap = dropGapAt(500f, m, 5)
        assertEquals(5, gap)
        assertEquals(3, dev.nymph.lyrics.logic.grid.moveTargetFromGap(gap, 1, 3))
        // inside the chunk: no move
        assertNull(dev.nymph.lyrics.logic.grid.moveTargetFromGap(dropGapAt(220f, m, 5), 1, 3))
    }

    @Test
    fun `handleAt finds the start and end handles below the cells`() {
        val sel = requireNotNull(CellSelection.of(0, 1, 3)) // edges at x=120 and x=320
        assertNull(handleAt(120f, 40f, m, sel, 24f)) // on the cells, not the handle zone
        assertEquals(SelectionEdge.START, handleAt(130f, 90f, m, sel, 24f))
        assertEquals(SelectionEdge.END, handleAt(300f, 90f, m, sel, 24f))
        assertNull(handleAt(220f, 90f, m, sel, 24f))
    }

    @Test
    fun `handleAt prefers the nearer handle of a one-cell selection`() {
        val sel = requireNotNull(CellSelection.of(0, 1, 2)) // edges at 120 and 220, slop larger than the cell
        assertEquals(SelectionEdge.START, handleAt(150f, 90f, m, sel, 80f))
        assertEquals(SelectionEdge.END, handleAt(190f, 90f, m, sel, 80f))
    }

    @Test
    fun `edge auto-scroll is zero in the middle and grows toward each edge`() {
        assertEquals(0f, edgeAutoScrollDelta(500f, 1000f, 100f, 10f))
        assertEquals(0f, edgeAutoScrollDelta(100f, 1000f, 100f, 10f))
        assertEquals(-5f, edgeAutoScrollDelta(50f, 1000f, 100f, 10f))
        assertEquals(-10f, edgeAutoScrollDelta(0f, 1000f, 100f, 10f))
        assertEquals(-10f, edgeAutoScrollDelta(-40f, 1000f, 100f, 10f))
        assertEquals(5f, edgeAutoScrollDelta(950f, 1000f, 100f, 10f))
        assertEquals(10f, edgeAutoScrollDelta(1200f, 1000f, 100f, 10f))
    }

    @Test
    fun `edge auto-scroll splits a narrow strip at its centre and ignores a zero-size strip`() {
        assertTrue(edgeAutoScrollDelta(40f, 100f, 100f, 10f) < 0f)
        assertTrue(edgeAutoScrollDelta(60f, 100f, 100f, 10f) > 0f)
        assertEquals(0f, edgeAutoScrollDelta(10f, 0f, 100f, 10f))
    }

    private val rows = listOf(RowSpan(0f, 100f), RowSpan(100f, 200f), RowSpan(200f, 300f), RowSpan(300f, 400f))

    @Test
    fun `reorder target stays put for a small drag`() {
        assertEquals(1, reorderTargetIndex(rows, 1, 0f))
        assertEquals(1, reorderTargetIndex(rows, 1, 40f))
        assertEquals(1, reorderTargetIndex(rows, 1, -40f))
    }

    @Test
    fun `reorder target passes a row once the dragged centre enters its span`() {
        assertEquals(1, reorderTargetIndex(rows, 1, 50f)) // centre exactly on row 2's top
        assertEquals(2, reorderTargetIndex(rows, 1, 60f))
        assertEquals(2, reorderTargetIndex(rows, 1, 140f))
        assertEquals(3, reorderTargetIndex(rows, 1, 160f))
        assertEquals(1, reorderTargetIndex(rows, 1, -50f)) // centre exactly on row 0's bottom
        assertEquals(3, reorderTargetIndex(rows, 1, 5000f))
        assertEquals(0, reorderTargetIndex(rows, 1, -60f))
        assertEquals(0, reorderTargetIndex(rows, 3, -5000f))
    }

    @Test
    fun `reorder target with unmeasured rows keeps their order`() {
        val partial = listOf(RowSpan(0f, 100f), null, RowSpan(200f, 300f))
        assertEquals(2, reorderTargetIndex(partial, 2, 0f))
        assertEquals(1, reorderTargetIndex(listOf(null, RowSpan(100f, 200f)), 1, 0f))
        assertEquals(5, reorderTargetIndex(listOf(null), 5, 0f))
    }
}
