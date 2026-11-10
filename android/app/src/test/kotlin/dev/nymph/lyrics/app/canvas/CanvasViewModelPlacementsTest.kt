package dev.nymph.lyrics.app.canvas

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.rename.RenameDialog
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvas
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelPlacementsTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val here = CanvasRef(CANVAS_ID, "Song")
    private val hooks = CanvasRef(CanvasId("c2"), "Hooks")
    private val a = snippet("a", rawText = "one")
    private val b = snippet("b", rawText = "two", y = 200.0, canvases = listOf(hooks, here))
    private val c = snippet("c", rawText = "three", x = 400.0)

    private val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c))))
    private val snippets = FakeSnippetRepository()
    private val updates = CanvasUpdates()

    private fun TestScope.vm(): CanvasViewModel {
        val vm = CanvasViewModel(CANVAS_ID, canvases, snippets, persistScope = backgroundScope, canvasUpdates = updates)
        runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.selectAll(vararg ids: String) {
        onLongPress(SnippetId(ids.first()))
        ids.drop(1).forEach { onTap(SnippetId(it)) }
    }

    @Test
    fun `a card move saves its placement on this canvas`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.moveCardBy(a.id, Point(5.0, 5.0))
        vm.endMove(a.id)
        runCurrent()
        assertEquals(listOf(CANVAS_ID), snippets.batchCanvases)
    }

    @Test
    fun `plain delete removes the placements on this canvas only`() = runTest(main.dispatcher) {
        snippets.removeResult = { id -> ApiResult.Success(RemovePlacementResponse(snippetDeleted = id != b.id)) }
        val vm = vm()
        vm.selectAll("a", "b")
        assertTrue(vm.ready().removalPlan!!.removesShared)
        assertTrue(vm.ready().removalPlan!!.needsConfirm)
        vm.deleteSelected()
        runCurrent()
        assertEquals(setOf(a.id, b.id), snippets.removed.toSet())
        assertEquals(listOf(CANVAS_ID, CANVAS_ID), snippets.removedFrom)
        assertTrue(snippets.deletedEverywhere.isEmpty())
        assertEquals(listOf(c), vm.ready().snippets)
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `a shared snippet alone needs no confirm`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.selectAll("b")
        val plan = vm.ready().removalPlan!!
        assertFalse(plan.needsConfirm)
        assertEquals(listOf(hooks), vm.ready().selectionOtherCanvases)
    }

    @Test
    fun `a removal failure keeps the snippet and reports the typed error`() = runTest(main.dispatcher) {
        snippets.removeResult = { ApiResult.Failure(ApiError.fromHttp(404, """{"detail":{"error":"placement_not_found","message":"x"}}""")) }
        val vm = vm()
        vm.selectAll("b")
        vm.events.test {
            vm.deleteSelected()
            runCurrent()
            assertEquals(CanvasEvent.Message("A snippet is no longer on this canvas. Reload to see the latest."), awaitItem())
        }
        assertEquals(listOf(a, b, c), vm.ready().snippets)
        assertEquals(setOf(b.id), vm.ready().selection.ids)
    }

    @Test
    fun `delete everywhere deletes the snippets`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.selectAll("b", "c")
        assertEquals(listOf(hooks), vm.ready().deleteEverywherePlan!!.otherCanvases)
        vm.deleteEverywhereSelected()
        runCurrent()
        assertEquals(setOf(b.id, c.id), snippets.deletedEverywhere.toSet())
        assertTrue(snippets.removed.isEmpty())
        assertEquals(listOf(a), vm.ready().snippets)
    }

    @Test
    fun `merge takes every original off this canvas even when the server kept a shared one`() = runTest(main.dispatcher) {
        val merged = snippet("m", rawText = "one\ntwo")
        snippets.mergeResult = { ApiResult.Success(MergeResponse(merged, listOf(a.id))) }
        val vm = vm()
        vm.selectAll("a", "b")
        vm.merge(MergeMode.VERTICAL)
        runCurrent()
        assertEquals(listOf(c, merged), vm.ready().snippets)
    }

    @Test
    fun `a move transfer takes the snippets off and leaves selection`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.selectAll("a", "b")
        vm.onTransferred(TransferResponse(hooks.id, listOf(a.id), listOf(b.id), removedFromSource = true), hooks)
        assertEquals(listOf(c), vm.ready().snippets)
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `a show transfer updates the badges and keeps the selection`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.selectAll("a", "b")
        vm.onTransferred(TransferResponse(hooks.id, listOf(a.id), listOf(b.id), removedFromSource = false), hooks)
        assertEquals(listOf(hooks, here), vm.snippet(a.id)!!.canvases)
        assertEquals(listOf(hooks, here), vm.snippet(b.id)!!.canvases)
        assertEquals(setOf(a.id, b.id), vm.ready().selection.ids)
    }

    @Test
    fun `tapping the title opens the rename dialog with it`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startRename()
        assertEquals(RenameDialog.Open(CANVAS_ID, "Song"), vm.rename.value)
        vm.dismissRename()
        assertEquals(RenameDialog.Closed, vm.rename.value)
    }

    @Test
    fun `a rename updates the header and badges and tells the list`() = runTest(main.dispatcher) {
        val vm = vm()
        val published = mutableListOf<Canvas>()
        backgroundScope.launch { updates.updates.collect { published += it } }
        runCurrent()
        vm.startRename()
        vm.submitRename("  Night drive ")
        runCurrent()
        assertEquals("Night drive", canvases.updates.single().second.title)
        assertEquals(null, canvases.updates.single().second.viewportState)
        assertEquals("Night drive", vm.ready().title)
        assertEquals(listOf(hooks, CanvasRef(CANVAS_ID, "Night drive")), vm.snippet(b.id)!!.canvases)
        assertEquals(RenameDialog.Closed, vm.rename.value)
        assertEquals(listOf(canvas(CANVAS_ID.value, "Night drive")), published)
    }

    @Test
    fun `a blank or overlong title is refused locally`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startRename()
        vm.submitRename("   ")
        assertEquals(RenameDialog.Open(CANVAS_ID, "Song", error = "Enter a title."), vm.rename.value)
        vm.submitRename("x".repeat(256))
        assertEquals(RenameDialog.Open(CANVAS_ID, "Song", error = "Titles can be at most 255 characters."), vm.rename.value)
        runCurrent()
        assertTrue(canvases.updates.isEmpty())
    }

    @Test
    fun `the server's invalid_canvas_title shows inline and keeps the old title`() = runTest(main.dispatcher) {
        canvases.updateResult = { _, _ ->
            ApiResult.Failure(ApiError.fromHttp(422, """{"detail":{"error":"invalid_canvas_title","message":"no"}}"""))
        }
        val vm = vm()
        vm.startRename()
        vm.submitRename("ok")
        runCurrent()
        val open = vm.rename.value as RenameDialog.Open
        assertEquals("The server rejected this title. Use 1–255 characters.", open.error)
        assertFalse(open.saving)
        assertEquals("Song", vm.ready().title)
    }
}
