package dev.nymph.lyrics.app.sync

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.CANVAS_GONE
import dev.nymph.lyrics.app.canvas.CanvasUiState
import dev.nymph.lyrics.app.canvas.CanvasViewModel
import dev.nymph.lyrics.app.canvaslist.CanvasListUiState
import dev.nymph.lyrics.app.canvaslist.CanvasListViewModel
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.status.HealthState
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.status.Outage
import dev.nymph.lyrics.model.status.UnreachableReason
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.conflict.EntityKind
import dev.nymph.lyrics.sync.conflict.Resolution
import dev.nymph.lyrics.sync.conflict.ReviewItem
import dev.nymph.lyrics.sync.conflict.ReviewSide
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.outbox.OpId
import dev.nymph.lyrics.sync.repo.LocalCanvasRepository
import dev.nymph.lyrics.sync.repo.LocalFirst
import dev.nymph.lyrics.sync.repo.LocalSnippetRepository
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The app on the local copy: ViewModels follow it, plus the sync UI's pure pieces. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineAppTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val c1 = CanvasId("c1")
    private val s1 = SnippetId("s1")
    private val t0 = "2026-11-01T10:00:00Z"

    private fun data() = LocalData(
        account = UserId("u"),
        canvases = listOf(Canvas(c1, UserId("u"), "Song", false, null, null, t0, t0, 1)),
        snippets = listOf(LocalSnippet(s1, SnippetType.VERSE, GridMode.AUTO, null, null, "hello", emptyList(), null, null, t0, t0, t0, 1)),
        placements = listOf(LocalPlacement(s1, c1, PlacementId("p"), 0.0, 0.0, 300.0, 200.0, 0, null)),
        snapshotAt = t0,
    )

    private fun local() = LocalFirst(LocalStore(InMemoryStoreFile(data())), SyncTrigger.None)

    private val edits = LocalEdits(now = { "2026-11-02T10:00:00Z" }, newId = { "n" })

    private fun TestScope.canvasVm(lf: LocalFirst): CanvasViewModel {
        val vm = CanvasViewModel(c1, LocalCanvasRepository(lf, edits), LocalSnippetRepository(lf, edits), persistScope = backgroundScope)
        runCurrent()
        return vm
    }

    @Test
    fun `the canvas opens from the copy and follows changes made elsewhere`() = runTest(main.dispatcher) {
        val lf = local()
        val vm = canvasVm(lf)
        assertEquals("hello", (vm.state.value as CanvasUiState.Ready).snippets.single().rawText)
        // e.g. the editor, or a snapshot that brought another device's edit
        LocalSnippetRepository(lf, edits).update(c1, s1, SnippetUpdateRequest(rawText = "changed"))
        runCurrent()
        assertEquals("changed", (vm.state.value as CanvasUiState.Ready).snippets.single().rawText)
    }

    @Test
    fun `a card being dragged keeps its position while the copy changes`() = runTest(main.dispatcher) {
        val lf = local()
        val vm = canvasVm(lf)
        vm.onLongPress(s1)
        vm.moveCardBy(s1, Point(50.0, 0.0))
        lf.store.update { d -> d.withSnippet(d.snippet(s1)!!.copy(rawText = "synced")) to Unit }
        runCurrent()
        val card = (vm.state.value as CanvasUiState.Ready).snippets.single()
        assertEquals("synced", card.rawText)
        assertEquals(50.0, card.positionX)
    }

    @Test
    fun `a canvas deleted from the copy can no longer be shown`() = runTest(main.dispatcher) {
        val lf = local()
        val vm = canvasVm(lf)
        lf.store.update { d -> d.withoutCanvas(c1) to Unit }
        runCurrent()
        assertEquals(CanvasUiState.Failed(CANVAS_GONE), vm.state.value)
    }

    @Test
    fun `the canvas list follows the copy and deletes locally`() = runTest(main.dispatcher) {
        val lf = local()
        val vm = CanvasListViewModel(LocalCanvasRepository(lf, edits))
        runCurrent()
        LocalCanvasRepository(lf, edits).create("Second")
        runCurrent()
        val ready = vm.state.value as CanvasListUiState.Ready
        assertEquals(listOf("Second", "Song"), ready.canvases.map { it.title })
        vm.delete(ready.canvases.last())
        runCurrent()
        assertEquals(listOf("Second"), (vm.state.value as CanvasListUiState.Ready).canvases.map { it.title })
        assertTrue(lf.store.data.value.snippets.isEmpty(), "the snippet only on it went too")
    }

    @Test
    fun `reachability comes from the health monitor`() {
        assertTrue(isOnline(HealthState.Unknown))
        assertFalse(isOnline(HealthState.Down(Outage.Unreachable(UnreachableReason.OFFLINE), 2, 0)))
        val down = HealthState.Down(Outage.Unreachable(UnreachableReason.NETWORK), 2, 0)
        assertTrue(cameBack(down, HealthState.BackOnline(1, 0)))
        assertFalse(cameBack(HealthState.Healthy, HealthState.Healthy))
        assertTrue(cameBack(HealthState.Unknown, HealthState.Healthy))
    }

    @Test
    fun `review wording follows the conflict`() {
        val item = ReviewItem(OpId(1), EntityKind.SNIPPET, "x", "why", ReviewSide.OfSnippet("a", SnippetType.VERSE, emptyList()), ReviewSide.Gone, emptyList())
        assertEquals("Keep server's (accept the deletion)", resolutionLabel(item, Resolution.KEEP_SERVERS))
        assertEquals("Discard mine", resolutionLabel(item.copy(server = null), Resolution.KEEP_SERVERS))
        assertEquals("Keep server's (drop the merge)", resolutionLabel(item.copy(isMerge = true), Resolution.KEEP_SERVERS))
        assertEquals(listOf("Type: verse", "Tags: Hook"), sideSummary(ReviewSide.OfSnippet("a", SnippetType.VERSE, listOf("Hook"))))
        assertEquals("Canvas title", kindLabel(EntityKind.CANVAS))
    }
}
