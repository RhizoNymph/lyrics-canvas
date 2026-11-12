package dev.nymph.lyrics.sync.snapshot

import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.SnapshotPlacement
import dev.nymph.lyrics.model.SnapshotSnippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnapshotMergeTest {
    private fun snippet(id: SnippetId, text: String, version: Int = 2) =
        SnapshotSnippet(id, SnippetType.VERSE, GridMode.AUTO, null, null, text, null, null, Fx.T0, Fx.T1, Fx.T1, version, listOf(Fx.t1))

    private fun placement(id: SnippetId, canvas: CanvasId, x: Double = 99.0) =
        SnapshotPlacement(id, canvas, PlacementId("sp-$id-$canvas"), x, 0.0, 300.0, 200.0, 0, null)

    /** The server: both canvases (c1 renamed), s1 changed on c1 only, s2 gone, the tag and word unchanged, a preset. */
    private val snapshot = SyncSnapshot(
        serverTime = "2026-11-03T00:00:00Z",
        canvases = listOf(Fx.canvas(Fx.c1, "One (server)", version = 2), Fx.canvas(Fx.c2, "Two")),
        placements = listOf(placement(Fx.s1, Fx.c1)),
        snippets = listOf(snippet(Fx.s1, "server text")),
        tags = listOf(Fx.serverTag(Fx.t1, "Hook", 1)),
        tagPresets = listOf(TagPreset(TagPresetId("p"), "P", "Hook", Fx.T0, Fx.T0)),
        wordBank = listOf(Fx.serverWord(Fx.w1, "moon", 1)),
    )

    private fun LocalData.queue(vararg ops: SyncOp) = withOutbox(ops.fold(outbox) { o, op -> o.enqueue(op) })

    @Test
    fun `the server wins for entities with no queued change, and entities missing from it go`() {
        val merged = SnapshotMerge.merge(Fx.data(), snapshot)
        assertEquals("One (server)", merged.canvas(Fx.c1)?.title)
        assertEquals("server text", merged.snippet(Fx.s1)?.rawText)
        assertEquals(2, merged.snippet(Fx.s1)?.version)
        assertEquals(99.0, merged.placement(Fx.s1, Fx.c1)?.positionX)
        assertNull(merged.placement(Fx.s1, Fx.c2), "the server took it off c2")
        assertNull(merged.snippet(Fx.s2))
        assertEquals(listOf("P"), merged.presets.map { it.name })
        assertEquals("2026-11-03T00:00:00Z", merged.snapshotAt)
    }

    @Test
    fun `an entity with a pending change keeps its local copy and placements`() {
        val local = Fx.data().queue(SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(rawText = "mine")), 1), SyncOp.RenameCanvas(Fx.c2, "Mine", 1))
        val merged = SnapshotMerge.merge(local, snapshot)
        assertEquals("first line\nsecond", merged.snippet(Fx.s1)?.rawText)
        assertEquals(10.0, merged.placement(Fx.s1, Fx.c1)?.positionX)
        assertNotNull(merged.placement(Fx.s1, Fx.c2))
        assertEquals("Two", merged.canvas(Fx.c2)?.title)
    }

    @Test
    fun `pending creates survive and pending deletes stay deleted`() {
        val created = SnippetId("new")
        val local = Fx.data()
            .withSnippet(Fx.content(created, "offline"))
            .withPlacement(Fx.placement(created, Fx.c2))
            .withoutCanvas(Fx.c1)
            .queue(SyncOp.CreateSnippet(created, Fx.c2, SnippetCreateRequest(0.0, 0.0)), SyncOp.DeleteCanvas(Fx.c1, 1))
        val merged = SnapshotMerge.merge(local, snapshot)
        assertNotNull(merged.snippet(created))
        assertNotNull(merged.placement(created, Fx.c2))
        assertNull(merged.canvas(Fx.c1))
        assertNull(merged.placement(Fx.s1, Fx.c1), "a placement needs its canvas")
    }

    @Test
    fun `a parked change keeps its local copy for the review`() {
        val local = Fx.data().queue(SyncOp.EditSnippet(Fx.s2, Fx.c1, checkNotNull(ContentPatch.of(rawText = "mine")), 1))
        val parked = local.withOutbox(local.outbox.park(local.outbox.entries[0].id, Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s2))))
        val merged = SnapshotMerge.merge(parked, snapshot)
        assertEquals("other", merged.snippet(Fx.s2)?.rawText)
    }
}
