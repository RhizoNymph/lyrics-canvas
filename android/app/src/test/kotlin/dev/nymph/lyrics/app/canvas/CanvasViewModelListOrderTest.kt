package dev.nymph.lyrics.app.canvas

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.list.InMemoryListSortStore
import dev.nymph.lyrics.app.canvas.list.ListSortStore
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.canvasDetail
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.list.ListSort
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Records layout updates and batches; everything else is the shared fake. */
private class RankRecorder(private val base: FakeSnippetRepository = FakeSnippetRepository()) : SnippetRepository by base {
    val updates = mutableListOf<Pair<SnippetId, SnippetUpdateRequest>>()
    val batches get() = base.batches
    var updateResult: ApiResult<Snippet>? = null

    fun failBatches(error: ApiError) {
        base.batchResult = ApiResult.Failure(error)
    }

    override suspend fun update(canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> {
        updates += id to body
        return updateResult ?: ApiResult.Success(snippet(id.value).copy(listRank = body.listRank ?: "a0"))
    }

    override suspend fun batchPosition(canvasId: CanvasId, updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> =
        base.batchPosition(canvasId, updates)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelListOrderTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    // Last edited: c, a, b. Created: b, c, a. A-Z: "apple"(b), "Mango"(c), "zest"(a). Custom: a, b, c.
    private val a = snippet("a", rawText = "zest", contentUpdatedAt = "2026-09-02 10:00:00+00:00", createdAt = "2026-01-01T00:00:00Z").copy(listRank = "a0")
    private val b = snippet("b", rawText = "apple", contentUpdatedAt = "2026-09-01 10:00:00+00:00", createdAt = "2026-03-01T00:00:00Z").copy(listRank = "a1")
    private val c = snippet("c", rawText = "Mango", contentUpdatedAt = "2026-09-03 10:00:00+00:00", createdAt = "2026-02-01T00:00:00Z").copy(listRank = "a2")

    private fun TestScope.vm(
        snippets: SnippetRepository = RankRecorder(),
        sorts: ListSortStore = InMemoryListSortStore(),
        list: Boolean = true,
    ): CanvasViewModel {
        val canvases = FakeCanvasRepository(ApiResult.Success(canvasDetail(listOf(a, b, c))))
        val vm = CanvasViewModel(CANVAS_ID, canvases, snippets, persistScope = backgroundScope, searchDebounceMs = 0, listSorts = sorts)
        runCurrent()
        if (list) vm.setMode(CanvasModeKind.LIST)
        return vm
    }

    private fun CanvasViewModel.ready(): CanvasUiState.Ready = state.value as CanvasUiState.Ready

    private fun CanvasViewModel.rows(): List<String> = ready().listRows.map { it.id.value }

    private fun CanvasViewModel.sort(): ListSort = (ready().mode as CanvasMode.SnippetList).order.sort

    private fun CanvasViewModel.rank(id: String) = ready().byId.getValue(SnippetId(id)).listRank

    // ---- Sort ----

    @Test
    fun `the list starts in the device's stored sort`() = runTest(main.dispatcher) {
        val vm = vm(sorts = InMemoryListSortStore(ListSort.CUSTOM))
        assertEquals(ListSort.CUSTOM, vm.sort())
        assertEquals(listOf("a", "b", "c"), vm.rows())
    }

    @Test
    fun `the default sort is last edited`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(ListSort.LAST_EDITED, vm.sort())
        assertEquals(listOf("c", "a", "b"), vm.rows())
    }

    @Test
    fun `each sort orders the rows`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setListSort(ListSort.CREATED)
        assertEquals(listOf("b", "c", "a"), vm.rows())
        vm.setListSort(ListSort.A_Z)
        assertEquals(listOf("b", "c", "a"), vm.rows())
        vm.setListSort(ListSort.CUSTOM)
        assertEquals(listOf("a", "b", "c"), vm.rows())
        vm.setListSort(ListSort.LAST_EDITED)
        assertEquals(listOf("c", "a", "b"), vm.rows())
    }

    @Test
    fun `the chosen sort is remembered on the device`() = runTest(main.dispatcher) {
        val store = InMemoryListSortStore()
        val vm = vm(sorts = store)
        vm.setListSort(ListSort.A_Z)
        runCurrent()
        assertEquals(ListSort.A_Z, store.sort.first())
    }

    @Test
    fun `a sort chosen on the canvas applies when the list is shown`() = runTest(main.dispatcher) {
        val vm = vm(list = false)
        vm.setListSort(ListSort.CUSTOM)
        vm.setMode(CanvasModeKind.LIST)
        assertEquals(ListSort.CUSTOM, vm.sort())
        assertEquals(listOf("a", "b", "c"), vm.rows())
    }

    @Test
    fun `a stored sort arriving late re-sorts the shown list`() = runTest(main.dispatcher) {
        val store = InMemoryListSortStore()
        val vm = vm(sorts = store)
        store.save(ListSort.CUSTOM)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), vm.rows())
    }

    // ---- Drag in Custom ----

    @Test
    fun `a drop in custom takes a key between its neighbours with one layout update`() = runTest(main.dispatcher) {
        val repo = RankRecorder()
        val vm = vm(snippets = repo, sorts = InMemoryListSortStore(ListSort.CUSTOM))
        vm.moveListRow(SnippetId("c"), 1) // a c b
        assertEquals(listOf("a", "c", "b"), vm.rows())
        assertEquals("a0V", vm.rank("c"))
        runCurrent()
        assertEquals(listOf(SnippetId("c") to SnippetUpdateRequest(listRank = "a0V")), repo.updates)
        assertTrue(repo.batches.isEmpty())
    }

    @Test
    fun `a failed layout update rolls the order back and says why`() = runTest(main.dispatcher) {
        val repo = RankRecorder().apply {
            updateResult = ApiResult.Failure(ApiError.Validation(422, "bad", PlacementErrorCode.INVALID_LIST_RANK.wire))
        }
        val vm = vm(snippets = repo, sorts = InMemoryListSortStore(ListSort.CUSTOM))
        val messages = mutableListOf<CanvasEvent>()
        backgroundScope.launch { vm.events.collect { messages += it } }
        runCurrent()
        vm.moveListRow(SnippetId("c"), 0)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), vm.rows())
        assertEquals("a2", vm.rank("c"))
        assertEquals(listOf(CanvasEvent.Message("The server refused the new list order. Try again.")), messages)
    }

    @Test
    fun `dropping a row where it was sends nothing`() = runTest(main.dispatcher) {
        val repo = RankRecorder()
        val vm = vm(snippets = repo, sorts = InMemoryListSortStore(ListSort.CUSTOM))
        vm.moveListRow(SnippetId("b"), 1)
        runCurrent()
        assertTrue(repo.updates.isEmpty() && repo.batches.isEmpty())
    }

    // ---- Drag in another sort ----

    @Test
    fun `a drop in another sort adopts it as the custom order in one batch and switches to custom`() = runTest(main.dispatcher) {
        val repo = RankRecorder()
        val store = InMemoryListSortStore()
        val vm = vm(snippets = repo, sorts = store)
        // Last edited: c a b; drag b to the top.
        vm.moveListRow(SnippetId("b"), 0)
        assertEquals(ListSort.CUSTOM, vm.sort())
        assertEquals(listOf("b", "c", "a"), vm.rows())
        runCurrent()
        assertEquals(
            listOf(listOf(PositionUpdate(SnippetId("b"), listRank = "a0"), PositionUpdate(SnippetId("c"), listRank = "a1"), PositionUpdate(SnippetId("a"), listRank = "a2"))),
            repo.batches,
        )
        assertTrue(repo.updates.isEmpty())
        assertEquals(ListSort.CUSTOM, store.sort.first())
    }

    @Test
    fun `the adopted order includes filtered-out snippets in their place`() = runTest(main.dispatcher) {
        val repo = RankRecorder()
        val vm = vm(snippets = repo)
        vm.editSearch("e") // "zest" (a) and "apple" (b); "Mango" (c) is hidden
        runCurrent()
        assertEquals(listOf("a", "b"), vm.rows())
        // Last edited is c a b; b dropped above a lands right before it: c b a.
        vm.moveListRow(SnippetId("b"), 0)
        runCurrent()
        assertEquals(listOf("c", "b", "a"), repo.batches.single().map { it.id.value })
        assertEquals(listOf("b", "a"), vm.rows())
    }

    @Test
    fun `a failed batch restores the ranks and the previous sort`() = runTest(main.dispatcher) {
        val repo = RankRecorder().apply { failBatches(ApiError.Network(java.io.IOException("down"))) }
        val store = InMemoryListSortStore()
        val vm = vm(snippets = repo, sorts = store)
        vm.moveListRow(SnippetId("b"), 0)
        runCurrent()
        assertEquals(ListSort.LAST_EDITED, vm.sort())
        assertEquals(listOf("c", "a", "b"), vm.rows())
        assertEquals(listOf("a0", "a1", "a2"), listOf("a", "b", "c").map { vm.rank(it) })
        assertEquals(ListSort.LAST_EDITED, store.sort.first())
    }

    @Test
    fun `long-press still selects`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onListLongPress(SnippetId("a"))
        assertEquals(setOf(SnippetId("a")), vm.ready().selection.ids)
    }

    @Test
    fun `a drop on the canvas view does nothing`() = runTest(main.dispatcher) {
        val repo = RankRecorder()
        val vm = vm(snippets = repo, list = false)
        vm.moveListRow(SnippetId("b"), 0)
        runCurrent()
        assertTrue(repo.updates.isEmpty() && repo.batches.isEmpty())
    }
}
