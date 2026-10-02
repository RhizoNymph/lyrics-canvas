package dev.nymph.lyrics.app.canvaslist

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasListViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeCanvasRepository(ApiResult.Failure(ApiError.NotFound(null))).apply {
        listResult = ApiResult.Success(listOf(canvas("1", "One"), canvas("2", "Two")))
    }

    @Test
    fun `lists canvases on start`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo)
        assertEquals(CanvasListUiState.Loading, vm.state.value)
        runCurrent()
        assertEquals(CanvasListUiState.Ready(listOf(canvas("1", "One"), canvas("2", "Two"))), vm.state.value)
    }

    @Test
    fun `refresh lists again`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo)
        runCurrent()
        repo.listResult = ApiResult.Success(emptyList())
        vm.refresh()
        runCurrent()
        assertEquals(CanvasListUiState.Ready(emptyList()), vm.state.value)
        assertEquals(2, repo.listCalls)
    }

    @Test
    fun `create adds the canvas and opens it`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo)
        runCurrent()
        vm.events.test {
            vm.create("  New song ")
            runCurrent()
            assertEquals(CanvasListEvent.Open(CanvasId("new")), awaitItem())
        }
        assertEquals(canvas("new", "New song"), (vm.state.value as CanvasListUiState.Ready).canvases.first())
    }

    @Test
    fun `create with a blank title uses the default`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo)
        runCurrent()
        vm.create(" ")
        runCurrent()
        assertEquals("Untitled Canvas", (vm.state.value as CanvasListUiState.Ready).canvases.first().title)
    }

    @Test
    fun `create failure is reported and the list kept`() = runTest(main.dispatcher) {
        repo.createResult = { ApiResult.Failure(ApiError.Network(java.io.IOException("x"))) }
        val vm = CanvasListViewModel(repo)
        runCurrent()
        vm.events.test {
            vm.create("A")
            runCurrent()
            assertEquals(CanvasListEvent.Message("Can't reach the server. Check your connection."), awaitItem())
        }
        assertEquals(2, (vm.state.value as CanvasListUiState.Ready).canvases.size)
    }
}
