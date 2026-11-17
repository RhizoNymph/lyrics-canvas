package dev.nymph.lyrics.app.canvas.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListDragTest {
    // Rows 100 px tall with a 10 px gap: 0..100, 110..210, 220..320.
    private val r0 = RowSpan(0, 0, 100)
    private val r1 = RowSpan(1, 110, 100)
    private val r2 = RowSpan(2, 220, 60)

    @Test
    fun `stays until its centre passes a neighbour's centre`() {
        assertEquals(DragStep.Stay, dragStep(r1, 50f, r0, r2))
        assertEquals(DragStep.Stay, dragStep(r1, -59f, r0, r2))
        assertEquals(DragStep.Stay, dragStep(r1, 0f, r0, r2))
    }

    @Test
    fun `moving down swaps with the row below and keeps the row under the finger`() {
        // r1 centre 160 + 100 = 260 > r2 centre 250. After the swap r1 starts at 220 + 60 - 100 = 180: moved 70.
        assertEquals(DragStep.Swap(2, -70f), dragStep(r1, 100f, r0, r2))
    }

    @Test
    fun `moving up swaps with the row above`() {
        // r1 centre 160 - 120 = 40 < r0 centre 50. After the swap r1 starts at 0: moved -110.
        assertEquals(DragStep.Swap(0, 110f), dragStep(r1, -120f, r0, r2))
    }

    @Test
    fun `no neighbour, no swap`() {
        assertEquals(DragStep.Stay, dragStep(r0, -500f, null, r1))
        assertEquals(DragStep.Stay, dragStep(r2, 500f, r1, null))
    }

    @Test
    fun `moved keeps the other rows in order`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").moved(0, 1))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
        assertEquals(listOf("a", "b", "c"), listOf("a", "b", "c").moved(1, 1))
    }

    @Test
    fun `edge scroll only near the viewport's ends, faster deeper in`() {
        assertEquals(0f, edgeScroll(200f, 300f, 0, 1000, 64f, 24f))
        assertEquals(-12f, edgeScroll(32f, 132f, 0, 1000, 64f, 24f))
        assertEquals(-24f, edgeScroll(-200f, -100f, 0, 1000, 64f, 24f))
        assertEquals(12f, edgeScroll(868f, 968f, 0, 1000, 64f, 24f))
    }
}
