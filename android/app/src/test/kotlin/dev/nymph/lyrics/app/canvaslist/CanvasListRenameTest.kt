package dev.nymph.lyrics.app.canvaslist

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.rename.RenameDialog
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasListRenameTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val one = canvas("1", "One")
    private val two = canvas("2", "Two")
    private val repo = FakeCanvasRepository(ApiResult.Failure(ApiError.NotFound(null))).apply {
        listResult = ApiResult.Success(listOf(one, two))
    }
    private val updates = CanvasUpdates()

    private fun CanvasListViewModel.canvases() = (state.value as CanvasListUiState.Ready).canvases

    @Test
    fun `rename updates the row in place without a reload`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo, updates)
        runCurrent()
        vm.startRename(two)
        assertEquals(RenameDialog.Open(CanvasId("2"), "Two"), vm.rename.value)
        vm.submitRename(" Deux ")
        assertEquals(RenameDialog.Open(CanvasId("2"), "Two", saving = true), vm.rename.value)
        runCurrent()
        assertEquals(CanvasId("2") to "Deux", repo.updates.single().let { it.first to it.second.title })
        assertEquals(listOf(one, canvas("2", "Deux")), vm.canvases())
        assertEquals(RenameDialog.Closed, vm.rename.value)
        assertEquals(1, repo.listCalls)
    }

    @Test
    fun `a refused title stays in the dialog`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo, updates)
        runCurrent()
        vm.startRename(one)
        vm.submitRename("")
        assertEquals("Enter a title.", (vm.rename.value as RenameDialog.Open).error)
        repo.updateResult = { _, _ -> ApiResult.Failure(ApiError.fromHttp(422, """{"detail":{"error":"invalid_canvas_title","message":"m"}}""")) }
        vm.submitRename("fine")
        runCurrent()
        assertEquals("The server rejected this title. Use 1–255 characters.", (vm.rename.value as RenameDialog.Open).error)
        assertEquals(listOf(one, two), vm.canvases())
    }

    @Test
    fun `renames and creations elsewhere reach the list`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo, updates)
        runCurrent()
        updates.publish(canvas("1", "Uno"))
        updates.publish(canvas("3", "Tres"))
        runCurrent()
        assertEquals(listOf(canvas("3", "Tres"), canvas("1", "Uno"), two), vm.canvases())
    }

    @Test
    fun `create trims, opens, and refuses an overlong title locally`() = runTest(main.dispatcher) {
        val vm = CanvasListViewModel(repo, updates)
        runCurrent()
        vm.events.test {
            vm.create("x".repeat(256))
            assertEquals(CanvasListEvent.Message("Titles can be at most 255 characters."), awaitItem())
            vm.create(" Song ")
            runCurrent()
            assertEquals(CanvasListEvent.Open(CanvasId("new")), awaitItem())
        }
        assertEquals(listOf("Song"), repo.created)
    }

    @Test
    fun `create shows the server's invalid_canvas_title`() = runTest(main.dispatcher) {
        repo.createResult = { ApiResult.Failure(ApiError.fromHttp(422, """{"detail":{"error":"invalid_canvas_title","message":"m"}}""")) }
        val vm = CanvasListViewModel(repo, updates)
        runCurrent()
        vm.events.test {
            vm.create("ok")
            runCurrent()
            assertEquals(CanvasListEvent.Message("The server rejected this title. Use 1–255 characters."), awaitItem())
        }
        assertTrue(vm.canvases().size == 2)
    }
}
