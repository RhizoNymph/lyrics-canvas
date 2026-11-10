package dev.nymph.lyrics.app.canvas.placements

import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.placements.CanvasTitleProblem
import dev.nymph.lyrics.logic.placements.DeleteEverywherePlan
import dev.nymph.lyrics.logic.placements.PlacementError
import dev.nymph.lyrics.logic.placements.RemovalPlan
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.PlacementErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacementMessagesTest {
    private val here = CanvasRef(CANVAS_ID, "Song")
    private val hooks = CanvasRef(CanvasId("c2"), "Hooks")
    private val only = snippet("a")
    private val shared = snippet("b", canvases = listOf(hooks, here))

    @Test
    fun `every placement code has its own message`() {
        val messages = PlacementErrorCode.entries.map { PlacementError.Rejected(it, 422, "server words").userMessage() }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.none { it.contains("server words") })
        assertEquals(SESSION_EXPIRED, PlacementError.SessionExpired.userMessage())
        assertEquals("boom", PlacementError.Http(500, "boom").userMessage())
        assertEquals("Can't reach the server. Check your connection.", PlacementError.Network("x").userMessage())
    }

    @Test
    fun `title problems`() {
        assertEquals("Enter a title.", CanvasTitleProblem.EMPTY.userMessage())
        assertEquals("Titles can be at most 255 characters.", CanvasTitleProblem.TOO_LONG.userMessage())
    }

    @Test
    fun `the delete label says remove when a snippet is on other canvases`() {
        assertEquals("Delete", RemovalPlan.of(listOf(only))!!.actionLabel())
        assertEquals("Remove from this canvas", RemovalPlan.of(listOf(only, shared))!!.actionLabel())
    }

    @Test
    fun `a removal of last copies confirms as before`() {
        assertEquals("Delete snippet?", RemovalPlan.of(listOf(only))!!.confirmTitle())
        assertEquals("Delete 2 snippets?", RemovalPlan.of(listOf(only, snippet("c")))!!.confirmTitle())
        assertEquals("This cannot be undone.", RemovalPlan.of(listOf(only))!!.confirmText())
    }

    @Test
    fun `a mixed removal says which are deleted for good`() {
        val plan = RemovalPlan.of(listOf(only, shared))!!
        assertEquals("Remove 2 snippets from this canvas?", plan.confirmTitle())
        assertEquals("1 of them is on no other canvas and will be deleted. This cannot be undone.", plan.confirmText())
    }

    @Test
    fun `delete everywhere names the other canvases`() {
        val plan = DeleteEverywherePlan.of(listOf(shared))!!
        assertEquals("Delete snippet everywhere?", plan.confirmTitle())
        assertEquals("Also removed from: Hooks. Recordings are deleted too. This cannot be undone.", plan.confirmText())
        assertEquals(
            "Not on any other canvas. Recordings are deleted too. This cannot be undone.",
            DeleteEverywherePlan.of(listOf(only))!!.confirmText(),
        )
    }
}
