package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.logic.canvas.Rect
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.SnippetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CardLayoutBadgeTest {
    private val here = CanvasRef(CANVAS_ID, "Song")
    private val other = CanvasRef(CanvasId("c2"), "Other")

    @Test
    fun `a snippet on one canvas has no badge`() {
        assertNull(cardLayout(snippet("a")).linkBadge)
    }

    @Test
    fun `the badge follows the type label in the header`() {
        val layout = cardLayout(snippet("a", type = SnippetType.VERSE, canvases = listOf(other, here)))
        val badge = layout.linkBadge!!
        assertEquals("⧉ 2", badge.label)
        // "VERSE": 14 + 5 * 7.5 + 6 = 57.5; "⧉ 2" is 3 characters: 12 + 18 = 30 wide.
        assertEquals(Rect(57.5, 7.0, 30.0, 16.0), badge.bounds)
        assertEquals(57.5 + 6.0, badge.labelX)
        assertEquals(7.0 + 3.5, badge.labelY)
    }

    @Test
    fun `the count is every canvas holding the snippet`() {
        val three = listOf(other, here, CanvasRef(CanvasId("c3"), "Third"))
        assertEquals("⧉ 3", cardLayout(snippet("a", canvases = three)).linkBadge!!.label)
    }
}
