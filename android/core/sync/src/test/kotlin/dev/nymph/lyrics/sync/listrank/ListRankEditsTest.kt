package dev.nymph.lyrics.sync.listrank

import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.SnapshotPlacement
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.apply.Edited
import dev.nymph.lyrics.sync.engine.withLocalRanks
import dev.nymph.lyrics.sync.outbox.LayoutPatch
import dev.nymph.lyrics.sync.outbox.Outbox
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.StoreJson
import dev.nymph.lyrics.sync.store.Views
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `list_rank` as an offline layout field: local edits, the outbox, the views and the snapshot. */
class ListRankEditsTest {
    private val edits = Fx.edits()

    private fun <T> Edited<T>.value(): T = (result as ApiResult.Success).value

    private fun <T> Edited<T>.error(): ApiError = (result as ApiResult.Failure).error

    private fun Edited<*>.ops() = data.outbox.entries.map { it.op }

    /** Sample data with ranks: on c1, s1 = a1 and s2 = a2; s1 on c2 = a0. */
    private fun ranked(): LocalData {
        val d = Fx.data()
        return d.copy(
            placements = d.placements.map {
                when {
                    it.snippetId == Fx.s1 && it.canvasId == Fx.c1 -> it.copy(listRank = "a1")
                    it.snippetId == Fx.s2 -> it.copy(listRank = "a2")
                    else -> it.copy(listRank = "a0")
                }
            },
        )
    }

    private fun LocalData.rank(id: SnippetId, canvas: CanvasId = Fx.c1) = placement(id, canvas)?.listRank

    // ---- Create ----

    @Test
    fun `an offline create goes on top of the canvas's custom order`() {
        val e = edits.createSnippet(ranked(), Fx.c1, SnippetCreateRequest(0.0, 0.0, rawText = "new"))
        val id = e.value().id
        assertEquals("a0", e.data.rank(id)) // keyBetween(null, "a1")
        assertEquals("a0", e.value().listRank)
    }

    @Test
    fun `a create on an empty canvas takes the first key`() {
        val d = ranked().withCanvas(Fx.canvas(CanvasId("c3"), "Empty"))
        val e = edits.createSnippet(d, CanvasId("c3"), SnippetCreateRequest(0.0, 0.0))
        assertEquals("a0", e.data.rank(e.value().id, CanvasId("c3")))
    }

    @Test
    fun `a create with a given rank uses it`() {
        val e = edits.createSnippet(ranked(), Fx.c1, SnippetCreateRequest(0.0, 0.0, listRank = "a5"))
        assertEquals("a5", e.data.rank(e.value().id))
        assertEquals("a5", (e.ops().single() as SyncOp.CreateSnippet).body.listRank)
    }

    @Test
    fun `a create with an invalid rank is refused like the server would`() {
        val d = ranked()
        val e = edits.createSnippet(d, Fx.c1, SnippetCreateRequest(0.0, 0.0, listRank = "a00"))
        assertSame(d, e.data)
        val error = e.error() as ApiError.Validation
        assertEquals(422, error.status)
        assertEquals(PlacementErrorCode.INVALID_LIST_RANK.wire, error.code)
    }

    @Test
    fun `the queued create is sent with the local placement's key`() {
        val e = edits.createSnippet(ranked(), Fx.c1, SnippetCreateRequest(0.0, 0.0, rawText = "new"))
        val batch = checkNotNull(e.data.outbox.nextBatch()).withLocalRanks(e.data)
        assertEquals("a0", (batch.first.op as SyncOp.CreateSnippet).body.listRank)
    }

    @Test
    fun `a create reordered before it syncs is sent with the newer key`() {
        val created = edits.createSnippet(ranked(), Fx.c1, SnippetCreateRequest(0.0, 0.0))
        val id = created.value().id
        val moved = edits.updateSnippet(created.data, Fx.c1, id, SnippetUpdateRequest(listRank = "a1V"))
        val batch = checkNotNull(moved.data.outbox.nextBatch()).withLocalRanks(moved.data)
        assertEquals("a1V", (batch.first.op as SyncOp.CreateSnippet).body.listRank)
    }

    @Test
    fun `withLocalRanks leaves other operations and given keys alone`() {
        val d = ranked()
        val op = SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(LayoutPatch.of(positionX = 1.0)))
        val batch = checkNotNull(d.withOutbox(d.outbox.enqueue(op)).outbox.nextBatch())
        assertEquals(batch, batch.withLocalRanks(d))
    }

    // ---- Update (layout) ----

    @Test
    fun `a rank-only update is a layout operation on this canvas's placement`() {
        val e = edits.updateSnippet(ranked(), Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "a3"))
        assertEquals(listOf(SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(LayoutPatch.of(listRank = "a3")))), e.ops())
        assertEquals("a3", e.data.rank(Fx.s1))
        assertEquals("a0", e.data.rank(Fx.s1, Fx.c2)) // the other canvas keeps its own order
        assertEquals("a3", e.value().listRank)
        assertEquals(Fx.data().snippet(Fx.s1)?.version, e.data.snippet(Fx.s1)?.version) // not content
    }

    @Test
    fun `an invalid rank update changes nothing`() {
        val d = ranked()
        val e = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "!"))
        assertSame(d, e.data)
        assertEquals(PlacementErrorCode.INVALID_LIST_RANK.wire, (e.error() as ApiError.Validation).code)
    }

    @Test
    fun `rank moves of one placement coalesce, last value wins, and keep a position`() {
        var d = ranked()
        d = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(positionX = 5.0)).data
        d = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "a3")).data
        d = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "a4")).data
        val op = d.outbox.entries.single().op as SyncOp.MoveSnippet
        assertEquals(checkNotNull(LayoutPatch.of(positionX = 5.0, listRank = "a4")), op.layout)
    }

    @Test
    fun `layout patches merge the rank field by field`() {
        val a = checkNotNull(LayoutPatch.of(listRank = "a1", zIndex = 2))
        val b = checkNotNull(LayoutPatch.of(listRank = "a2"))
        assertEquals(checkNotNull(LayoutPatch.of(listRank = "a2", zIndex = 2)), a.then(b))
        assertEquals(a, a.then(checkNotNull(LayoutPatch.of(zIndex = 2))))
        assertEquals("a1", LayoutPatch.of(SnippetUpdateRequest(listRank = "a1"))?.listRank)
    }

    // ---- Batch re-rank ----

    @Test
    fun `a batch of ranks queues one layout operation per placement`() {
        val e = edits.batchPosition(ranked(), Fx.c1, listOf(PositionUpdate(Fx.s2, listRank = "a0"), PositionUpdate(Fx.s1, listRank = "a1")))
        assertEquals(2, e.value().updated)
        assertEquals(
            listOf(
                SyncOp.MoveSnippet(Fx.s2, Fx.c1, checkNotNull(LayoutPatch.of(listRank = "a0"))),
                SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(LayoutPatch.of(listRank = "a1"))),
            ),
            e.ops(),
        )
        assertEquals("a0", e.data.rank(Fx.s2))
        assertEquals(10.0, e.data.placement(Fx.s1, Fx.c1)?.positionX) // position untouched
    }

    @Test
    fun `a batch with one invalid rank changes nothing`() {
        val d = ranked()
        val e = edits.batchPosition(d, Fx.c1, listOf(PositionUpdate(Fx.s2, listRank = "a0"), PositionUpdate(Fx.s1, listRank = "a10")))
        assertSame(d, e.data)
        assertEquals(PlacementErrorCode.INVALID_LIST_RANK.wire, (e.error() as ApiError.Validation).code)
    }

    @Test
    fun `an empty batch item is skipped`() {
        val e = edits.batchPosition(ranked(), Fx.c1, listOf(PositionUpdate(Fx.s1)))
        assertEquals(0, e.value().updated)
        assertTrue(e.data.outbox.isEmpty)
    }

    @Test
    fun `a re-rank coalesces with a pending rank move of the same placement`() {
        var d = ranked()
        d = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "a5")).data
        d = edits.batchPosition(d, Fx.c1, listOf(PositionUpdate(Fx.s1, listRank = "a0"), PositionUpdate(Fx.s2, listRank = "a1"))).data
        assertEquals(2, d.outbox.entries.size)
        assertEquals("a0", (d.outbox.entries.first().op as SyncOp.MoveSnippet).layout.listRank)
    }

    // ---- Merge and transfer ----

    @Test
    fun `a merge keeps the first block's rank`() {
        val d = ranked()
        val e = edits.merge(d, Fx.c1, checkNotNull(MergeRequest.of(listOf(Fx.s1, Fx.s2), MergeMode.VERTICAL)))
        val first = e.value().deletedIds.first()
        assertEquals(d.rank(first), e.data.rank(e.value().snippet.id))
    }

    @Test
    fun `a transfer puts the snippets on top of the target's custom order`() {
        val d = ranked()
        val e = edits.transfer(d, Fx.c1, checkNotNull(TransferRequest.of(listOf(Fx.s2), Fx.c2, TransferMode.SHOW)))
        val rank = checkNotNull(e.data.rank(Fx.s2, Fx.c2))
        assertTrue(rank < "a0", rank) // above s1's a0 on c2
    }

    // ---- Views, snapshot, stored copy ----

    @Test
    fun `canvas detail carries each placement's rank`() {
        val detail = checkNotNull(Views.canvasDetail(ranked(), Fx.c1))
        assertEquals(mapOf(Fx.s1 to "a1", Fx.s2 to "a2"), detail.snippets.associate { it.id to it.listRank })
    }

    @Test
    fun `snapshot placements decode list_rank`() {
        val json = """{"server_time":"t","placements":[{"snippet_id":"s1","canvas_id":"c1","placement_id":"p","position_x":0,
            "position_y":0,"width":1,"height":1,"z_index":0,"list_rank":"a0V"}]}"""
        val snapshot = LyricsJson.decodeFromString(SyncSnapshot.serializer(), json)
        assertEquals("a0V", snapshot.placements.single().listRank)
        assertEquals("a0V", LocalPlacement.of(snapshot.placements.single()).listRank)
    }

    @Test
    fun `a stored placement from before ranks reads the default key`() {
        val json = """{"snippet_id":"s1","canvas_id":"c1","placement_id":"p","position_x":0,"position_y":0,"width":1,"height":1,"z_index":0}"""
        assertEquals("a0", StoreJson.decodeFromString(LocalPlacement.serializer(), json).listRank)
        val snapshotJson = """{"snippet_id":"s1","canvas_id":"c1","placement_id":"p","position_x":0,"position_y":0,"width":1,"height":1,"z_index":0}"""
        assertEquals("a0", LyricsJson.decodeFromString(SnapshotPlacement.serializer(), snapshotJson).listRank)
    }

    @Test
    fun `the rank survives the store's own encoding, outbox included`() {
        val d = edits.updateSnippet(ranked(), Fx.c1, Fx.s1, SnippetUpdateRequest(listRank = "a3")).data
        val text = StoreJson.encodeToString(LocalData.serializer(), d)
        val back = StoreJson.decodeFromString(LocalData.serializer(), text)
        assertEquals("a3", back.rank(Fx.s1))
        assertEquals("a3", (back.outbox.entries.single().op as SyncOp.MoveSnippet).layout.listRank)
        assertNull(Outbox.EMPTY.nextBatch())
    }
}
