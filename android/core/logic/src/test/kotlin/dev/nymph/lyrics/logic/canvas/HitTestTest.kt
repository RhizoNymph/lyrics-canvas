package dev.nymph.lyrics.logic.canvas

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HitTestTest {
    @Test
    fun `card bounds come from position and size times the snippet scale`() {
        val s = snippet("a", x = 10.0, y = 20.0, width = 300.0, height = 100.0)
        assertEquals(Rect(10.0, 20.0, 300.0, 100.0), cardBounds(s))
        assertEquals(Rect(10.0, 20.0, 600.0, 200.0), cardBounds(s, 2.0))
    }

    @Test
    fun `snippet scale is clamped like the web (0_3 to 4)`() {
        assertEquals(0.3, clampSnippetScale(0.1))
        assertEquals(4.0, clampSnippetScale(9.0))
        assertEquals(1.5, clampSnippetScale(1.5))
        val s = snippet("a", width = 100.0, height = 100.0)
        assertEquals(400.0, cardBounds(s, 10.0).width)
    }

    @Test
    fun `screen bounds apply the viewport transform`() {
        val s = snippet("a", x = 10.0, y = 20.0, width = 300.0, height = 100.0)
        val v = Viewport(-10.0, 5.0, 2.0)
        assertEquals(Rect(0.0, 50.0, 600.0, 200.0), cardScreenBounds(s, 1.0, v))
    }

    @Test
    fun `rect contains is inclusive of the top-left and exclusive of the bottom-right`() {
        val r = Rect(0.0, 0.0, 10.0, 10.0)
        assertTrue(r.contains(Point(0.0, 0.0)))
        assertTrue(r.contains(Point(9.99, 9.99)))
        assertFalse(r.contains(Point(10.0, 5.0)))
        assertFalse(r.contains(Point(-0.1, 5.0)))
    }

    @Test
    fun `hit test returns the topmost card by z index`() {
        val low = snippet("low", x = 0.0, y = 0.0, width = 100.0, height = 100.0, zIndex = 5)
        val high = snippet("high", x = 50.0, y = 50.0, width = 100.0, height = 100.0, zIndex = 9)
        assertEquals(SnippetId("high"), hitTest(listOf(high, low), Point(60.0, 60.0))?.id)
        assertEquals(SnippetId("low"), hitTest(listOf(high, low), Point(10.0, 10.0))?.id)
    }

    @Test
    fun `hit test breaks z ties by list order, later drawn on top`() {
        val first = snippet("first", width = 100.0, height = 100.0)
        val second = snippet("second", width = 100.0, height = 100.0)
        assertEquals(SnippetId("second"), hitTest(listOf(first, second), Point(5.0, 5.0))?.id)
        assertEquals(SnippetId("first"), hitTest(listOf(second, first), Point(5.0, 5.0))?.id)
    }

    @Test
    fun `hit test uses per-snippet scales and misses empty space`() {
        val s = snippet("a", width = 100.0, height = 100.0)
        assertNull(hitTest(listOf(s), Point(150.0, 50.0)))
        assertEquals(SnippetId("a"), hitTest(listOf(s), Point(150.0, 50.0), mapOf(SnippetId("a") to 2.0))?.id)
        assertNull(hitTest(emptyList(), Point(0.0, 0.0)))
    }

    @Test
    fun `screen hit test converts through the viewport`() {
        val s = snippet("a", x = 100.0, y = 100.0, width = 50.0, height = 50.0)
        val v = Viewport(0.0, 0.0, 2.0)
        assertEquals(SnippetId("a"), hitTestScreen(listOf(s), Point(210.0, 210.0), v)?.id)
        assertNull(hitTestScreen(listOf(s), Point(110.0, 110.0), v))
    }

    @Test
    fun `draw order sorts by z with list order as the tie-break`() {
        val a = snippet("a", zIndex = 2)
        val b = snippet("b", zIndex = 1)
        val c = snippet("c", zIndex = 2)
        assertEquals(listOf("b", "a", "c"), drawOrder(listOf(a, b, c)).map { it.id.value })
    }
}
