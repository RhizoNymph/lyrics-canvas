package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaterializePositionTest {
    private val anchor = AnchorRect(SnippetId("a"), 100.0, 200.0, 250.0)
    private val other = AnchorRect(SnippetId("b"), 900.0, 900.0, 150.0)
    private val viewport = Viewport(-100.0, 50.0, 2.0)
    private val viewSize = ViewSize(800.0, 600.0)
    // world centre = size / (2 * zoom) - viewport offset
    private val centre = Point(800.0 / 4 + 100, 600.0 / 4 - 50)

    @Test
    fun `places the first materialization to the right of a single anchor on the same row`() {
        val p = materializePosition(listOf(anchor), emptyMap(), viewport, viewSize)
        assertEquals(Point(100.0 + 250 + MATERIALIZE_GAP_X, 200.0), p)
    }

    @Test
    fun `steps down by the row step for each prior materialization from the same anchor`() {
        val p = materializePosition(listOf(anchor), mapOf(SnippetId("a") to 2), viewport, viewSize)
        assertEquals(Point(100.0 + 250 + MATERIALIZE_GAP_X, 200.0 + 2 * MATERIALIZE_STEP_Y), p)
    }

    @Test
    fun `ignores counts recorded for other anchors`() {
        assertEquals(200.0, materializePosition(listOf(anchor), mapOf(SnippetId("b") to 5), viewport, viewSize).y)
    }

    @Test
    fun `falls back to the viewport centre in world coordinates with no anchor snippet`() {
        assertEquals(centre, materializePosition(emptyList(), emptyMap(), viewport, viewSize))
    }

    @Test
    fun `falls back to the viewport centre with more than one anchor snippet`() {
        assertEquals(centre, materializePosition(listOf(anchor, other), emptyMap(), viewport, viewSize))
    }

    @Test
    fun `exports the documented spacing constants`() {
        assertEquals(40.0, MATERIALIZE_GAP_X)
        assertEquals(60.0, MATERIALIZE_STEP_Y)
    }
}
