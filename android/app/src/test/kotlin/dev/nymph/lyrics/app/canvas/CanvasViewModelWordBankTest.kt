package dev.nymph.lyrics.app.canvas

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.wordbank.DraftCreateResult
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelWordBankTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val a = snippet("a", rawText = "one")
    private val snippets = FakeSnippetRepository()

    @Test
    fun `a draft becomes a snippet at the viewport centre, shown selected`() = runTest(main.dispatcher) {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a), ViewportState(-100.0, -50.0, 2.0)))), snippets, backgroundScope)
        runCurrent()
        vm.setViewSize(ViewSize(400.0, 800.0))
        val result = vm.createSnippetFromText("night\nlight", SnippetType.HOOK)
        assertTrue(result is DraftCreateResult.Created)
        val body = snippets.created.single()
        assertEquals(200.0 to 250.0, body.positionX to body.positionY)
        assertEquals("night\nlight", body.rawText)
        assertEquals(SnippetType.HOOK, body.snippetType)
        val ready = vm.state.value as CanvasUiState.Ready
        val created = (result as DraftCreateResult.Created).snippet
        assertTrue(ready.snippets.any { it.id == created.id })
        assertEquals(setOf(created.id), ready.selection.ids)
    }

    @Test
    fun `list mode reveals the new snippet`() = runTest(main.dispatcher) {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a), null))), snippets, backgroundScope)
        runCurrent()
        vm.setMode(CanvasModeKind.LIST)
        vm.events.test {
            val result = vm.createSnippetFromText("rain", SnippetType.FREESTYLE) as DraftCreateResult.Created
            assertEquals(CanvasEvent.RevealSnippet(result.snippet.id), awaitItem())
        }
    }

    @Test
    fun `before the canvas loads there is nowhere to create`() = runTest(main.dispatcher) {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a), null))), snippets, backgroundScope)
        assertEquals(DraftCreateResult.CanvasNotLoaded, vm.createSnippetFromText("rain", SnippetType.FREESTYLE))
    }
}
