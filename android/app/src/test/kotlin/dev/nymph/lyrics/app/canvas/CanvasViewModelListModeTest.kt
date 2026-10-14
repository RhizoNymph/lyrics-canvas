package dev.nymph.lyrics.app.canvas

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelListModeTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // Load order a, b, c; content edited c (newest), then a, then b.
    private val a = snippet("a", rawText = "one", contentUpdatedAt = "2026-09-02 10:00:00+00:00")
    private val b = snippet("b", rawText = "two", y = 200.0, contentUpdatedAt = "2026-09-01 10:00:00+00:00")
    private val c = snippet("c", rawText = "three", x = 400.0, contentUpdatedAt = "2026-09-03 10:00:00.5+00:00")

    private fun TestScope.vm(
        snippets: FakeSnippetRepository = FakeSnippetRepository(),
        canvases: FakeCanvasRepository = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c)))),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): CanvasViewModel {
        val vm = CanvasViewModel(CANVAS_ID, canvases, snippets, persistScope = backgroundScope, savedState = savedState)
        runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.rows(): List<String> = ready().listRows.map { it.id.value }

    private fun CanvasViewModel.selected(): Set<SnippetId> = ready().selection.ids

    @Test
    fun `starts on the canvas`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(CanvasMode.Canvas, vm.ready().mode)
        assertEquals(CanvasModeKind.CANVAS, vm.ready().mode.kind)
        assertEquals(emptyList<Snippet>(), vm.ready().listRows)
    }

    @Test
    fun `list mode shows every snippet, last content edit first`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(CanvasModeKind.LIST, vm.ready().mode.kind)
        assertEquals(listOf("c", "a", "b"), vm.rows())
        // The canvas order is untouched.
        assertEquals(listOf(a, b, c), vm.ready().snippets)
    }

    @Test
    fun `switching back to the canvas drops the list`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        vm.setMode(CanvasModeKind.CANVAS)
        assertEquals(CanvasMode.Canvas, vm.ready().mode)
    }

    @Test
    fun `the mode is kept in the saved state`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(savedState = saved)
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(CanvasModeKind.LIST.name, saved.get<String>(CANVAS_MODE_KEY))
        vm.setMode(CanvasModeKind.CANVAS)
        assertEquals(CanvasModeKind.CANVAS.name, saved.get<String>(CANVAS_MODE_KEY))
    }

    @Test
    fun `a recreated ViewModel opens in the saved mode, freshly sorted`() = runTest(main.dispatcher) {
        val vm = vm(savedState = SavedStateHandle(mapOf(CANVAS_MODE_KEY to CanvasModeKind.LIST.name)))
        assertEquals(CanvasModeKind.LIST, vm.ready().mode.kind)
        assertEquals(listOf("c", "a", "b"), vm.rows())
    }

    @Test
    fun `an unknown saved value falls back to the canvas`() = runTest(main.dispatcher) {
        val vm = vm(savedState = SavedStateHandle(mapOf(CANVAS_MODE_KEY to "SIDEWAYS")))
        assertEquals(CanvasMode.Canvas, vm.ready().mode)
    }

    @Test
    fun `setting the mode before the canvas loads is remembered`() = runTest(main.dispatcher) {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c))))
        val vm = CanvasViewModel(CANVAS_ID, canvases, FakeSnippetRepository(), persistScope = backgroundScope)
        vm.setMode(CanvasModeKind.LIST)
        runCurrent()
        assertEquals(listOf("c", "a", "b"), vm.rows())
    }

    @Test
    fun `reload re-sorts the list`() = runTest(main.dispatcher) {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c))))
        val vm = vm(canvases = canvases)
        vm.setMode(CanvasModeKind.LIST)
        canvases.detail = ApiResult.Success(canvasDetail(listOf(a, b.copy(contentUpdatedAt = "2026-09-09 00:00:00+00:00"), c)))
        vm.reload()
        runCurrent()
        assertEquals(listOf("b", "c", "a"), vm.rows())
    }

    @Test
    fun `rows hold still while snippets change in place`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        vm.insertMaterialized(snippet("m", contentUpdatedAt = "2026-08-01 00:00:00+00:00"))
        // New rows go on top; the rows the user was looking at keep their order.
        assertEquals(listOf("m", "c", "a", "b"), vm.rows())
    }

    @Test
    fun `returning from the editor re-sorts the list`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        vm.replaceSnippet(b.copy(rawText = "edited", contentUpdatedAt = "2026-09-10 00:00:00+00:00"))
        assertEquals(listOf("b", "c", "a"), vm.rows())
        assertEquals("edited", vm.ready().listRows.first().rawText)
    }

    @Test
    fun `tapping a row opens the editor`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        vm.events.test {
            vm.onTap(a.id)
            assertEquals(CanvasEvent.OpenEditor(a.id), awaitItem())
        }
    }

    @Test
    fun `long-pressing a row selects it without starting a move, and taps toggle`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(snippets = snippets)
        vm.setMode(CanvasModeKind.LIST)
        vm.onListLongPress(a.id)
        assertEquals(setOf(a.id), vm.selected())
        // A stray move for the row does nothing: no move was started.
        vm.moveCardBy(a.id, dev.nymph.lyrics.logic.canvas.Point(50.0, 50.0))
        vm.endMove(a.id)
        runCurrent()
        assertEquals(a, vm.ready().byId[a.id])
        assertTrue(snippets.batches.isEmpty())
        vm.events.test {
            vm.onTap(b.id)
            expectNoEvents()
        }
        assertEquals(setOf(a.id, b.id), vm.selected())
        vm.onTap(a.id)
        vm.onTap(b.id)
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `long press on an unknown row does nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        vm.onListLongPress(SnippetId("nope"))
        assertEquals(Selection.Idle, vm.ready().selection)
    }

    @Test
    fun `the selection is shared between list and canvas`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onLongPress(a.id)
        vm.endMove(a.id)
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(setOf(a.id), vm.selected())
        vm.onListLongPress(c.id)
        vm.setMode(CanvasModeKind.CANVAS)
        assertEquals(setOf(a.id, c.id), vm.selected())
    }

    @Test
    fun `merge from the list puts the merged snippet on top, selected`() = runTest(main.dispatcher) {
        val merged = snippet("m", rawText = "one\nthree", contentUpdatedAt = "2026-09-04 00:00:00+00:00")
        val snippets = FakeSnippetRepository().apply {
            mergeResult = { ApiResult.Success(MergeResponse(merged, listOf(a.id, c.id))) }
        }
        val vm = vm(snippets = snippets)
        vm.setMode(CanvasModeKind.LIST)
        vm.onListLongPress(c.id)
        vm.onTap(a.id)
        vm.merge(MergeMode.HORIZONTAL)
        runCurrent()
        assertEquals(MergeMode.HORIZONTAL, snippets.merges.single().mode)
        assertEquals(listOf("m", "b"), vm.rows())
        assertEquals(setOf(merged.id), vm.selected())
    }

    @Test
    fun `delete from the list removes the rows`() = runTest(main.dispatcher) {
        val snippets = FakeSnippetRepository()
        val vm = vm(snippets = snippets)
        vm.setMode(CanvasModeKind.LIST)
        vm.onListLongPress(a.id)
        vm.onTap(c.id)
        vm.deleteSelected()
        runCurrent()
        assertEquals(setOf(a.id, c.id), snippets.deleted.toSet())
        assertEquals(listOf("b"), vm.rows())
        assertEquals(Selection.Idle, vm.ready().selection)
    }
}
