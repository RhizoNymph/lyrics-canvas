package dev.nymph.lyrics.app.canvas

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.voice.takeResponse
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelVoiceTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val a = snippet("a", rawText = "one", contentUpdatedAt = "2026-10-01T00:00:00Z")

    private fun TestScope.vm(viewport: ViewportState? = ViewportState(-100.0, -50.0, 2.0)): CanvasViewModel {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a), viewport))), FakeSnippetRepository(), backgroundScope)
        runCurrent()
        return vm
    }

    private fun CanvasViewModel.ready() = state.value as CanvasUiState.Ready

    @Test
    fun `the target is the viewport centre in canvas coordinates`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setViewSize(ViewSize(400.0, 800.0))
        // centre = size / (2 zoom) - pan = (100 + 100, 200 + 50)
        assertEquals(VoiceTakeTarget.New(200.0, 250.0), vm.voiceTarget())
    }

    @Test
    fun `list mode uses the last canvas viewport`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setViewSize(ViewSize(400.0, 800.0))
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(VoiceTakeTarget.New(200.0, 250.0), vm.voiceTarget())
    }

    @Test
    fun `a canvas never viewed falls back to the origin`() = runTest(main.dispatcher) {
        val vm = vm(viewport = null)
        assertEquals(VoiceTakeTarget.New(0.0, 0.0), vm.voiceTarget())
    }

    @Test
    fun `no target before the canvas loads`() = runTest(main.dispatcher) {
        val vm = CanvasViewModel(CANVAS_ID, FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a)))), FakeSnippetRepository(), backgroundScope)
        assertNull(vm.voiceTarget())
    }

    @Test
    fun `a created snippet is inserted and selected`() = runTest(main.dispatcher) {
        val vm = vm()
        val created = snippet("v1", rawText = "hello\nworld", x = 200.0, y = 250.0)
        vm.events.test {
            vm.onVoiceTakeSaved(takeResponse(created))
            expectNoEvents()
        }
        assertEquals(listOf(a, created), vm.ready().snippets)
        assertEquals(Selection.of(setOf(SnippetId("v1"))), vm.ready().selection)
    }

    @Test
    fun `in list mode the created snippet is revealed`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setMode(CanvasModeKind.LIST)
        val created = snippet("v1", rawText = "hello")
        vm.events.test {
            vm.onVoiceTakeSaved(takeResponse(created))
            assertEquals(CanvasEvent.RevealSnippet(SnippetId("v1")), awaitItem())
        }
        assertEquals(SnippetId("v1"), vm.ready().listRows.first().id)
    }

    @Test
    fun `an appended snippet replaces its old copy without selecting`() = runTest(main.dispatcher) {
        val vm = vm()
        val appended = a.copy(rawText = "one\ntwo")
        vm.onVoiceTakeSaved(takeResponse(appended, created = false))
        assertEquals(listOf(appended), vm.ready().snippets)
        assertEquals(Selection.Idle, vm.ready().selection)
    }
}
