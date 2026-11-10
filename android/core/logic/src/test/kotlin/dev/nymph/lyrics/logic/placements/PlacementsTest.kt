package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacementsTest {
    private val here = CanvasRef(CanvasId("c1"), "Song")
    private val alpha = CanvasRef(CanvasId("c2"), "alpha")
    private val beta = CanvasRef(CanvasId("c3"), "Beta")
    private val only = snippet("only")
    private val shared = snippet("shared", canvases = listOf(alpha, here))
    private val shared2 = snippet("shared2", canvases = listOf(beta, here, alpha))

    @Test
    fun `other canvases leave out the snippet's own canvas`() {
        assertEquals(emptyList<CanvasRef>(), otherCanvases(only))
        assertEquals(listOf(alpha), otherCanvases(shared))
        assertEquals(listOf(beta, alpha), otherCanvases(shared2))
    }

    @Test
    fun `the link badge counts canvases only when there is more than one`() {
        assertNull(linkBadgeCount(only))
        assertEquals(2, linkBadgeCount(shared))
        assertEquals(3, linkBadgeCount(shared2))
        assertEquals("⧉ 3", linkBadgeLabel(3))
    }

    @Test
    fun `also-on text names the other canvases`() {
        assertNull(alsoOnText(only))
        assertEquals("Also on: alpha", alsoOnText(shared))
        assertEquals("Also on: Beta, alpha", alsoOnText(shared2))
    }

    @Test
    fun `nothing selected is no removal`() {
        assertNull(RemovalPlan.of(emptyList()))
    }

    @Test
    fun `last copies delete as before, with the confirm`() {
        val plan = RemovalPlan.of(listOf(only, snippet("only2")))!!
        assertEquals(listOf(SnippetId("only"), SnippetId("only2")), plan.ids)
        assertFalse(plan.removesShared)
        assertTrue(plan.needsConfirm)
        assertEquals(2, plan.lastCopyIds.size)
    }

    @Test
    fun `shared snippets are only removed from this canvas, without a confirm`() {
        val plan = RemovalPlan.of(listOf(shared, shared2))!!
        assertTrue(plan.removesShared)
        assertFalse(plan.needsConfirm)
        assertEquals(emptyList<SnippetId>(), plan.lastCopyIds)
    }

    @Test
    fun `a mixed selection is a removal that confirms its last copies`() {
        val plan = RemovalPlan.of(listOf(shared, only))!!
        assertTrue(plan.removesShared)
        assertTrue(plan.needsConfirm)
        assertEquals(listOf(SnippetId("only")), plan.lastCopyIds)
        assertEquals(setOf(SnippetId("shared")), plan.sharedIds)
    }

    @Test
    fun `delete everywhere names every other canvas once, sorted`() {
        assertNull(DeleteEverywherePlan.of(emptyList()))
        val plan = DeleteEverywherePlan.of(listOf(shared2, only, shared))!!
        assertEquals(listOf(SnippetId("shared2"), SnippetId("only"), SnippetId("shared")), plan.ids)
        assertEquals(listOf(alpha, beta), plan.otherCanvases)
        assertEquals(emptyList<CanvasRef>(), DeleteEverywherePlan.of(listOf(only))!!.otherCanvases)
    }

    @Test
    fun `canvas refs sort by lowercase title then id`() {
        val a2 = CanvasRef(CanvasId("c0"), "Alpha")
        assertEquals(listOf(a2, alpha, beta), sortCanvasRefs(listOf(beta, alpha, a2)))
    }

    @Test
    fun `a move takes the transferred snippets off this canvas`() {
        val response = TransferResponse(CanvasId("c9"), listOf(SnippetId("only")), listOf(SnippetId("shared")), removedFromSource = true)
        val after = applyTransfer(listOf(only, shared, shared2), response, CanvasRef(CanvasId("c9"), "Target"))
        assertEquals(listOf(SnippetId("shared2")), after.map { it.id })
    }

    @Test
    fun `a show adds the target to the badges, once`() {
        val target = CanvasRef(CanvasId("c2"), "alpha")
        val response = TransferResponse(CanvasId("c2"), listOf(SnippetId("only")), listOf(SnippetId("shared")), removedFromSource = false)
        val after = applyTransfer(listOf(only, shared, shared2), response, target).associateBy { it.id.value }
        assertEquals(listOf(alpha, here), after.getValue("only").canvases)
        assertEquals(listOf(alpha, here), after.getValue("shared").canvases)
        assertEquals(shared2, after.getValue("shared2"))
    }

    @Test
    fun `a rename updates this canvas's title in every badge`() {
        val after = withCanvasRenamed(listOf(only, shared2), CanvasId("c1"), "zeta")
        assertEquals(listOf(CanvasRef(CanvasId("c1"), "zeta")), after[0].canvases)
        assertEquals(listOf(alpha, beta, CanvasRef(CanvasId("c1"), "zeta")), after[1].canvases)
    }

    @Test
    fun `transfer messages`() {
        val r = { placed: Int, already: Int, removed: Boolean ->
            TransferResponse(CanvasId("t"), List(placed) { SnippetId("p$it") }, List(already) { SnippetId("a$it") }, removed)
        }
        assertEquals("Moved 3 snippets to Hooks", transferMessage(TransferMode.MOVE, r(3, 0, true), "Hooks"))
        assertEquals("Moved 1 snippet to Hooks", transferMessage(TransferMode.MOVE, r(1, 0, true), "Hooks"))
        assertEquals("Showing 3 snippets on Hooks (1 was already there)", transferMessage(TransferMode.SHOW, r(2, 1, false), "Hooks"))
        assertEquals("Showing 2 snippets on Hooks (2 were already there)", transferMessage(TransferMode.SHOW, r(0, 2, false), "Hooks"))
        assertEquals("Moved 2 snippets to Hooks (1 was already there)", transferMessage(TransferMode.MOVE, r(1, 1, true), "Hooks"))
    }
}
