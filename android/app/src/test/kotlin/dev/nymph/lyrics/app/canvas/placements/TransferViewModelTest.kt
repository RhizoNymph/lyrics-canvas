package dev.nymph.lyrics.app.canvas.placements

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private fun canvas(id: String, title: String, updated: String) =
        Canvas(CanvasId(id), UserId("u1"), title, isPublic = false, createdAt = "", updatedAt = updated)

    private val current = canvas(CANVAS_ID.value, "Song", "2026-11-09")
    private val hooks = canvas("c2", "Hooks", "2026-11-01")
    private val verses = canvas("c3", "Verses", "2026-11-05")
    private val canvases = FakeCanvasRepository(ApiResult.Failure(ApiError.NotFound(null))).apply {
        listResult = ApiResult.Success(listOf(hooks, current, verses))
        createResult = { ApiResult.Success(canvas("c9", it, "2026-11-09")) }
    }
    private val snippets = FakeSnippetRepository()
    private val updates = CanvasUpdates()
    private val transferred = mutableListOf<Pair<TransferResponse, CanvasRef>>()
    private val port = object : PlacementCanvasPort {
        override fun onTransferred(response: TransferResponse, target: CanvasRef) {
            transferred += response to target
        }
    }
    private val a = SnippetId("a")
    private val b = SnippetId("b")

    private fun vm() = TransferViewModel(CANVAS_ID, canvases, snippets, port, updates)

    private fun TransferViewModel.picking() = state.value as TransferUiState.Picking

    @Test
    fun `opening lists the other canvases newest first`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a, b))
        assertEquals(CanvasChoices.Loading, vm.picking().choices)
        runCurrent()
        assertEquals(listOf(verses, hooks), vm.picking().rows)
        vm.setQuery("HOO")
        assertEquals(listOf(hooks), vm.picking().rows)
    }

    @Test
    fun `an empty selection opens nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(TransferMode.SHOW, emptySet())
        assertEquals(TransferUiState.Closed, vm.state.value)
    }

    @Test
    fun `a failed load can be retried`() = runTest(main.dispatcher) {
        canvases.listResult = ApiResult.Failure(ApiError.Network(IOException("x")))
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a))
        runCurrent()
        assertEquals(CanvasChoices.Failed("Can't reach the server. Check your connection."), vm.picking().choices)
        canvases.listResult = ApiResult.Success(listOf(hooks))
        vm.retryLoad()
        runCurrent()
        assertEquals(listOf(hooks), vm.picking().rows)
    }

    @Test
    fun `choosing moves the snippets and offers to open the target`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a, b))
        runCurrent()
        vm.events.test {
            vm.choose(hooks)
            runCurrent()
            assertEquals(TransferEvent.Done("Moved 2 snippets to Hooks", hooks.id), awaitItem())
        }
        val (source, request) = snippets.transfers.single()
        assertEquals(CANVAS_ID, source)
        assertEquals(listOf(a, b), request.snippetIds)
        assertEquals(hooks.id, request.targetCanvasId)
        assertEquals(TransferMode.MOVE, request.mode)
        assertEquals(CanvasRef(hooks.id, "Hooks"), transferred.single().second)
        assertEquals(TransferUiState.Closed, vm.state.value)
    }

    @Test
    fun `show reports the snippets already there`() = runTest(main.dispatcher) {
        snippets.transferResult = { ApiResult.Success(TransferResponse(it.targetCanvasId, listOf(a), listOf(b), removedFromSource = false)) }
        val vm = vm()
        vm.open(TransferMode.SHOW, setOf(a, b))
        runCurrent()
        vm.events.test {
            vm.choose(verses)
            runCurrent()
            assertEquals(TransferEvent.Done("Showing 2 snippets on Verses (1 was already there)", verses.id), awaitItem())
        }
    }

    @Test
    fun `a refused transfer stays in the sheet with the typed message`() = runTest(main.dispatcher) {
        snippets.transferResult = { ApiResult.Failure(ApiError.fromHttp(404, """{"detail":{"error":"canvas_not_found","message":"x"}}""")) }
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a))
        runCurrent()
        vm.choose(hooks)
        runCurrent()
        assertEquals("That canvas no longer exists.", vm.picking().error)
        assertEquals(false, vm.picking().busy)
        assertTrue(transferred.isEmpty())
    }

    @Test
    fun `the source canvas is never a target`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a))
        runCurrent()
        vm.choose(current)
        runCurrent()
        assertTrue(snippets.transfers.isEmpty())
        assertEquals("Pick a different canvas: the snippets are already here.", vm.picking().error)
    }

    @Test
    fun `new canvas creates it, tells the list, then transfers to it`() = runTest(main.dispatcher) {
        val published = mutableListOf<Canvas>()
        backgroundScope.launch { updates.updates.collect { published += it } }
        val vm = vm()
        vm.open(TransferMode.SHOW, setOf(a))
        runCurrent()
        vm.startNewCanvas()
        assertEquals(NewCanvasPrompt(), vm.picking().newCanvas)
        vm.events.test {
            vm.submitNewCanvas("  Fresh ")
            runCurrent()
            assertEquals(TransferEvent.Done("Showing 1 snippet on Fresh", CanvasId("c9")), awaitItem())
        }
        assertEquals(listOf("Fresh"), canvases.created)
        assertEquals(listOf(CanvasId("c9")), published.map { it.id })
        assertEquals(CanvasId("c9"), snippets.transfers.single().second.targetCanvasId)
    }

    @Test
    fun `a blank new canvas title takes the default and an overlong one is refused`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a))
        runCurrent()
        vm.startNewCanvas()
        vm.submitNewCanvas("x".repeat(256))
        assertEquals(NewCanvasPrompt("Titles can be at most 255 characters."), vm.picking().newCanvas)
        assertTrue(canvases.created.isEmpty())
        vm.submitNewCanvas("   ")
        runCurrent()
        assertEquals(listOf("Untitled Canvas"), canvases.created)
    }

    @Test
    fun `a failed create keeps the prompt open with the error`() = runTest(main.dispatcher) {
        canvases.createResult = { ApiResult.Failure(ApiError.fromHttp(422, """{"detail":{"error":"invalid_canvas_title","message":"m"}}""")) }
        val vm = vm()
        vm.open(TransferMode.MOVE, setOf(a))
        runCurrent()
        vm.startNewCanvas()
        vm.submitNewCanvas("x")
        runCurrent()
        assertEquals(NewCanvasPrompt("The server rejected this title. Use 1–255 characters."), vm.picking().newCanvas)
        assertEquals(false, vm.picking().busy)
        assertTrue(snippets.transfers.isEmpty())
    }
}
