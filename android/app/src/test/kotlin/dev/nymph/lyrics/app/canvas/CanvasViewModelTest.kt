package dev.nymph.lyrics.app.canvas

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.catSnippet
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.logic.canvas.MergeError
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val a = snippet("a", rawText = "one", x = 0.0, y = 0.0)
    private val b = snippet("b", rawText = "two", x = 0.0, y = 200.0)
    private val c = snippet("c", rawText = "three", x = 400.0, y = 0.0)

    private fun TestScope.vm(
        detail: ApiResult<CanvasDetail> = ApiResult.Success(canvasDetail(listOf(a, b, c))),
        canvases: FakeCanvasRepository = FakeCanvasRepository(detail),
        snippets: FakeSnippetRepository = FakeSnippetRepository(),
    ): CanvasViewModel {
        val vm = CanvasViewModel(CANVAS_ID, canvases, snippets, persistScope = backgroundScope, viewportSaveDelayMs = 1_000)
        runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.selected(): Set<SnippetId> = (ready().selection as? Selection.Selecting)?.ids ?: emptySet()

    @Test
    fun `load shows the canvas with its saved viewport and nothing selected`() = runTest(main.dispatcher) {
        val vm = vm(ApiResult.Success(canvasDetail(listOf(a, b), ViewportState(10.0, 20.0, 2.0))))
        val ready = vm.ready()
        assertEquals("Song", ready.title)
        assertEquals(listOf(a, b), ready.snippets)
        assertEquals(Viewport(10.0, 20.0, 2.0), ready.viewport)
        assertEquals(Selection.Idle, ready.selection)
    }

    @Test
    fun `load clamps a saved zoom outside the range`() = runTest(main.dispatcher) {
        val vm = vm(ApiResult.Success(canvasDetail(listOf(a), ViewportState(0.0, 0.0, 50.0))))
        assertEquals(5.0, vm.ready().viewport.zoom)
    }

    @Test
    fun `load failure becomes a failed state with a readable message`() = runTest(main.dispatcher) {
        val vm = vm(ApiResult.Failure(ApiError.NotFound("Canvas not found")))
        assertEquals(CanvasUiState.Failed("Canvas not found"), vm.state.value)
    }

    @Test
    fun `reload after a failure loads again`() = runTest(main.dispatcher) {
        val repo = FakeCanvasRepository(ApiResult.Failure(ApiError.Network(java.io.IOException("offline"))))
        val vm = vm(canvases = repo)
        assertTrue(vm.state.value is CanvasUiState.Failed)
        repo.detail = ApiResult.Success(canvasDetail(listOf(a)))
        vm.reload()
        runCurrent()
        assertEquals(listOf(a), vm.ready().snippets)
    }

    @Test
    fun `tap on a card while not selecting opens the editor`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.events.test {
            vm.onTap(a.id)
            assertEquals(CanvasEvent.OpenEditor(a.id), awaitItem())
        }
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `tap on empty canvas while not selecting does nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.events.test {
            vm.onTap(null)
            expectNoEvents()
        }
    }

    @Test
    fun `long press enters selection mode and taps toggle until empty`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.endMove(a.id)
        assertEquals(setOf(a.id), vm.selected())
        vm.onTap(b.id)
        assertEquals(setOf(a.id, b.id), vm.selected())
        vm.onTap(null)
        assertEquals(setOf(a.id, b.id), vm.selected())
        vm.onTap(a.id)
        assertEquals(setOf(b.id), vm.selected())
        vm.onTap(b.id)
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `taps while selecting never open the editor`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.events.test {
            vm.onTap(b.id)
            expectNoEvents()
        }
    }

    @Test
    fun `long press on a card while selecting adds it`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.onLongPress(b.id)
        assertEquals(setOf(a.id, b.id), vm.selected())
    }

    @Test
    fun `exit selection clears it`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.exitSelection()
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `merge replaces the originals with the merged snippet and selects it`() = runTest(main.dispatcher) {
        val merged = snippet("m", rawText = "one\ntwo")
        val snippets = FakeSnippetRepository().apply {
            mergeResult = { ApiResult.Success(MergeResponse(merged, listOf(a.id, b.id))) }
        }
        val vm = vm(snippets = snippets)
        vm.onLongPress(a.id)
        vm.onTap(b.id)
        vm.merge(MergeMode.VERTICAL)
        runCurrent()
        val request = snippets.merges.single()
        assertEquals(setOf(a.id, b.id), request.snippetIds.toSet())
        assertEquals(MergeMode.VERTICAL, request.mode)
        assertEquals(listOf(c, merged), vm.ready().snippets)
        assertEquals(setOf(merged.id), vm.selected())
        assertNull(vm.ready().mergeError)
        assertEquals(false, vm.ready().busy)
    }

    @Test
    fun `merge failure keeps everything and reports the error`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository().apply {
            mergeResult = { ApiResult.Failure(ApiError.NotFound("Snippet not found")) }
        }
        val vm = vm(snippets = snippets)
        vm.onLongPress(a.id)
        vm.onTap(b.id)
        vm.events.test {
            vm.merge(MergeMode.HORIZONTAL)
            runCurrent()
            assertEquals(CanvasEvent.Message("Snippet not found"), awaitItem())
        }
        assertEquals(listOf(a, b, c), vm.ready().snippets)
        assertEquals(setOf(a.id, b.id), vm.selected())
        assertEquals(MergeError.NotFound("Snippet not found"), vm.ready().mergeError)
    }

    @Test
    fun `merge with one snippet selected never calls the server`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(snippets = snippets)
        vm.onLongPress(a.id)
        vm.merge(MergeMode.VERTICAL)
        runCurrent()
        assertTrue(snippets.merges.isEmpty())
        assertEquals(MergeError.TooFewSnippets, vm.ready().mergeError)
    }

    @Test
    fun `moving a card updates it live and commits batch-position once on release`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(ApiResult.Success(canvasDetail(listOf(a, b), ViewportState(0.0, 0.0, 2.0))), snippets = snippets)
        vm.onLongPress(a.id)
        vm.moveCardBy(a.id, Point(10.0, 0.0))
        vm.moveCardBy(a.id, Point(10.0, 20.0))
        runCurrent()
        // Screen deltas are divided by the zoom.
        assertEquals(Point(10.0, 10.0), vm.ready().snippets.first().let { Point(it.positionX, it.positionY) })
        assertTrue(snippets.batches.isEmpty())
        vm.endMove(a.id)
        runCurrent()
        assertEquals(listOf(listOf(PositionUpdate(a.id, 10.0, 10.0))), snippets.batches)
    }

    @Test
    fun `a long press without movement commits nothing`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(snippets = snippets)
        vm.onLongPress(a.id)
        vm.endMove(a.id)
        runCurrent()
        assertTrue(snippets.batches.isEmpty())
    }

    @Test
    fun `a failed move commit puts the card back`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository().apply { batchResult = ApiResult.Failure(ApiError.Server(500, null)) }
        val vm = vm(snippets = snippets)
        vm.onLongPress(b.id)
        vm.moveCardBy(b.id, Point(5.0, 5.0))
        vm.events.test {
            vm.endMove(b.id)
            runCurrent()
            assertEquals(CanvasEvent.Message("Server error (500)"), awaitItem())
        }
        assertEquals(b, vm.snippet(b.id))
    }

    @Test
    fun `pinch zooms about the centroid and pans with the fingers`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.transform(centroid = Point(100.0, 100.0), pan = Point(0.0, 0.0), zoom = 2.0)
        // The world point under the centroid stays put.
        assertEquals(Point(100.0, 100.0), vm.ready().viewport.worldToScreen(Point(100.0, 100.0)))
        assertEquals(2.0, vm.ready().viewport.zoom)
        vm.transform(centroid = Point(110.0, 100.0), pan = Point(10.0, 0.0), zoom = 1.0)
        assertEquals(Point(110.0, 100.0), vm.ready().viewport.worldToScreen(Point(100.0, 100.0)))
        vm.transform(centroid = Point(0.0, 0.0), pan = Point(0.0, 0.0), zoom = 100.0)
        assertEquals(5.0, vm.ready().viewport.zoom)
    }

    @Test
    fun `viewport saves are debounced to the last change`() = runTest(main.dispatcher) {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a))))
        val vm = vm(canvases = canvases)
        vm.panBy(Point(10.0, 0.0))
        advanceTimeBy(500)
        vm.panBy(Point(10.0, 0.0))
        advanceTimeBy(500)
        vm.panBy(Point(0.0, 5.0))
        advanceTimeBy(999)
        assertTrue(canvases.updates.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(ViewportState(20.0, 5.0, 1.0)), canvases.updates.map { it.second.viewportState })
        advanceUntilIdle()
        assertEquals(1, canvases.updates.size)
    }

    @Test
    fun `leaving flushes a pending viewport save at once and only once`() = runTest(main.dispatcher) {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a))))
        val vm = vm(canvases = canvases)
        vm.panBy(Point(3.0, 4.0))
        vm.flushViewport()
        runCurrent()
        assertEquals(listOf(ViewportState(3.0, 4.0, 1.0)), canvases.updates.map { it.second.viewportState })
        advanceUntilIdle()
        vm.flushViewport()
        advanceUntilIdle()
        assertEquals(1, canvases.updates.size)
    }

    @Test
    fun `an unchanged viewport is never saved`() = runTest(main.dispatcher) {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a))))
        val vm = vm(canvases = canvases)
        vm.flushViewport()
        advanceUntilIdle()
        assertTrue(canvases.updates.isEmpty())
    }

    @Test
    fun `new snippet is created at the viewport centre and opened`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(ApiResult.Success(canvasDetail(listOf(a), ViewportState(-100.0, 0.0, 2.0))), snippets = snippets)
        vm.setViewSize(ViewSize(400.0, 800.0))
        vm.events.test {
            vm.createSnippetAtCenter()
            runCurrent()
            val created = vm.ready().snippets.last()
            assertEquals(CanvasEvent.OpenEditor(created.id), awaitItem())
        }
        val body = snippets.created.single()
        // Centre (200, 400) screen -> world (200 / 2 + 100, 400 / 2).
        assertEquals(Point(200.0, 200.0), Point(body.positionX, body.positionY))
    }

    @Test
    fun `an edited snippet replaces the old one in place`() = runTest(main.dispatcher) {
        val vm = vm()
        val edited = b.copy(rawText = "edited")
        vm.replaceSnippet(edited)
        assertEquals(listOf(a, edited, c), vm.ready().snippets)
    }

    @Test
    fun `delete removes the selected snippets that the server deleted`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository().apply {
            deleteResult = { id -> if (id == b.id) ApiResult.Failure(ApiError.Server(500, "boom")) else ApiResult.Success(Unit) }
        }
        val vm = vm(snippets = snippets)
        vm.onLongPress(a.id)
        vm.onTap(b.id)
        vm.deleteSelected()
        runCurrent()
        assertEquals(setOf(a.id, b.id), snippets.deleted.toSet())
        assertEquals(listOf(b, c), vm.ready().snippets)
        assertEquals(setOf(b.id), vm.selected())
    }

    @Test
    fun `hit test uses the current viewport`() = runTest(main.dispatcher) {
        val vm = vm(ApiResult.Success(canvasDetail(listOf(catSnippet("s1", x = 100.0, y = 50.0)), ViewportState(0.0, 0.0, 2.0))))
        assertEquals(SnippetId("s1"), vm.hitTest(Point(210.0, 110.0)))
        assertNull(vm.hitTest(Point(150.0, 50.0)))
    }

    @Test
    fun `materialized snippets are inserted and exposed to the rhyme port`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        val made: Snippet = snippet("r1", rawText = "hat")
        vm.insertMaterialized(made)
        assertEquals(made, vm.ready().snippets.last())
        val view = vm.currentRhymeView()!!
        assertEquals(setOf(a.id), view.selectedIds)
        assertEquals(made, view.snippets[made.id])
    }
}
