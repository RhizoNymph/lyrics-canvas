package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CullingTest {
    private val view = ViewSize(400.0, 800.0)

    @Test
    fun `cards fully outside the view are culled`() {
        val inside = snippet("in", x = 10.0, y = 10.0)
        val right = snippet("right", x = 500.0, y = 10.0)
        val above = snippet("above", x = 10.0, y = -400.0)
        assertEquals(listOf("in"), visibleCards(listOf(inside, right, above), Viewport.IDENTITY, view).map { it.id.value })
    }

    @Test
    fun `a card partly on screen is kept, and the shadow margin counts`() {
        val partly = snippet("partly", x = -250.0, y = 0.0, width = 300.0)
        val shadowOnly = snippet("shadow", x = -302.0, y = 0.0, width = 300.0)
        val gone = snippet("gone", x = -310.0, y = 0.0, width = 300.0)
        assertEquals(listOf("partly", "shadow"), visibleCards(listOf(partly, shadowOnly, gone), Viewport.IDENTITY, view).map { it.id.value })
    }

    @Test
    fun `culling follows the viewport and keeps draw order`() {
        val far = snippet("far", x = 1000.0, y = 0.0, zIndex = 2)
        val near = snippet("near", x = 0.0, y = 0.0, zIndex = 1)
        // Pan so world x 1000 is at screen x 0 at zoom 0.5: (1000 + x) * 0.5 = 0 => x = -1000.
        val vp = Viewport(-1000.0, 0.0, 0.5)
        assertEquals(listOf("far"), visibleCards(listOf(far, near), vp, view).map { it.id.value })
        val zoomedOut = Viewport(0.0, 0.0, 0.2)
        assertEquals(listOf("near", "far"), visibleCards(listOf(far, near), zoomedOut, view).map { it.id.value })
    }

    @Test
    fun `intersects is strict on touching edges`() {
        assertTrue(intersects(Rect(0.0, 0.0, 10.0, 10.0), Rect(5.0, 5.0, 10.0, 10.0)))
        assertFalse(intersects(Rect(0.0, 0.0, 10.0, 10.0), Rect(10.0, 0.0, 10.0, 10.0)))
    }

    @Test
    fun `background grid lines follow the web InfiniteGrid offsets`() {
        val lines = backgroundGridLines(Viewport(10.0, -20.0, 1.0), ViewSize(120.0, 60.0))
        assertEquals(listOf(10.0, 60.0, 110.0), lines.xs)
        // (-20 * 1) % 50 = -20: the first line starts above the view.
        assertEquals(listOf(-20.0, 30.0), lines.ys)
    }

    @Test
    fun `background grid is skipped when lines would be denser than 8 units`() {
        val lines = backgroundGridLines(Viewport(0.0, 0.0, 0.15), ViewSize(120.0, 60.0))
        assertTrue(lines.xs.isEmpty() && lines.ys.isEmpty())
    }
}
