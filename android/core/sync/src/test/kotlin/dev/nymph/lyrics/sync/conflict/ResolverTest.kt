package dev.nymph.lyrics.sync.conflict

import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.LayoutPatch
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SnippetVersion
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ResolverTest {
    private var n = 0
    private val resolver = Resolver(newId = { "new${++n}" }, now = { Fx.T1 })
    private val t2 = TagId("t2")

    private fun edit(text: String) = SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(rawText = text)), 1)
    private val move = SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(LayoutPatch.of(positionX = 5.0)))

    /** Local s1 = "mine" tagged t1 (and t2 exists); [ops] queued, the first parked with [conflict]. */
    private fun parked(conflict: Conflict, vararg ops: SyncOp): LocalData {
        val d = Fx.data().withTag(Fx.tag(t2, "Two")).withSnippet(Fx.content(Fx.s1, "mine", tags = listOf(Fx.t1)))
        val queued = ops.fold(d.outbox) { o, op -> o.enqueue(op) }
        return d.withOutbox(queued.park(queued.entries[0].id, conflict))
    }

    private val server = Fx.serverSnippet(Fx.s1, "theirs", 7, tags = listOf(Tag(t2, "Two", "#000000")), type = SnippetType.HOOK)
    private val changed = Conflict.Changed(EntityRef.Snippet(Fx.s1), ServerCopy.OfSnippet(server))

    private fun LocalData.resolve(resolution: Resolution): LocalData {
        val r = resolver.resolve(this, outbox.entries[0].id, resolution)
        return (r as Resolved.Done).data
    }

    @Test
    fun `choices follow the user decisions`() {
        assertEquals(listOf(Resolution.KEEP_SERVERS, Resolution.OVERWRITE_WITH_MINE, Resolution.SAVE_MINE_AS_NEW), resolutionsFor(changed))
        assertEquals(listOf(Resolution.KEEP_SERVERS, Resolution.SAVE_MINE_AS_NEW), resolutionsFor(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1))))
        assertEquals(listOf(Resolution.KEEP_SERVERS, Resolution.OVERWRITE_WITH_MINE), resolutionsFor(Conflict.Changed(EntityRef.Tag(Fx.t1), ServerCopy.OfTag(Fx.serverTag(Fx.t1, "x", 2)))))
        assertEquals(listOf(Resolution.KEEP_SERVERS), resolutionsFor(Conflict.DeletedOnServer(EntityRef.Canvas(Fx.c1))))
        assertEquals(listOf(Resolution.KEEP_SERVERS, Resolution.RETRY_MERGE), resolutionsFor(Conflict.MergeChanged(EntityRef.Snippet(Fx.s1))))
    }

    @Test
    fun `a choice that is not offered, or a change no longer parked, is refused`() {
        val d = parked(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1)), edit("x"))
        assertEquals(Resolved.Refused(ResolveError.NOT_OFFERED), resolver.resolve(d, d.outbox.entries[0].id, Resolution.OVERWRITE_WITH_MINE))
        val pending = Fx.data().let { it.withOutbox(it.outbox.enqueue(edit("x"))) }
        assertEquals(Resolved.Refused(ResolveError.NOT_PARKED), resolver.resolve(pending, pending.outbox.entries[0].id, Resolution.KEEP_SERVERS))
    }

    @Nested
    inner class KeepServers {
        @Test
        fun `takes the server's copy, drops my content changes and keeps my layout`() {
            val d = parked(changed, edit("mine"), move, SyncOp.SnippetTag(Fx.s1, t2, true, 1)).resolve(Resolution.KEEP_SERVERS)
            assertEquals("theirs", d.snippet(Fx.s1)?.rawText)
            assertEquals(7, d.snippet(Fx.s1)?.version)
            assertEquals(listOf<SyncOp>(move), d.outbox.entries.map { it.op })
        }

        @Test
        fun `deleted on the server removes it locally with every queued change`() {
            val d = parked(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1)), edit("mine"), move).resolve(Resolution.KEEP_SERVERS)
            assertNull(d.snippet(Fx.s1))
            assertTrue(d.outbox.isEmpty)
        }

        @Test
        fun `dropping a create drops what depended on it`() {
            val created = SnippetId("c")
            val d = Fx.data().withSnippet(Fx.content(created, "x")).withPlacement(Fx.placement(created, Fx.c1))
            val o = d.outbox.enqueue(SyncOp.CreateSnippet(created, Fx.c1, SnippetCreateRequest(0.0, 0.0)))
                .enqueue(SyncOp.SnippetTag(created, Fx.t1, true, 1)).enqueue(edit("other"))
            val p = d.withOutbox(o.park(o.entries[0].id, Conflict.Rejected(EntityRef.Snippet(created), "canvas gone")))
            val r = p.resolve(Resolution.KEEP_SERVERS)
            assertNull(r.snippet(created))
            assertEquals(listOf(edit("other")), r.outbox.entries.map { it.op })
        }

        @Test
        fun `discarding my delete restores the server's copy`() {
            val d = Fx.data().withoutSnippet(Fx.s1)
            val o = d.outbox.enqueue(SyncOp.DeleteSnippet(Fx.s1, 1))
            val r = d.withOutbox(o.park(o.entries[0].id, changed)).resolve(Resolution.KEEP_SERVERS)
            assertEquals("theirs", r.snippet(Fx.s1)?.rawText)
            assertNotNull(r.placement(Fx.s1, Fx.c1))
        }
    }

    @Test
    fun `overwrite resends all of mine on the server's version, with tag differences`() {
        val d = parked(changed, edit("mine"), move).resolve(Resolution.OVERWRITE_WITH_MINE)
        val ops = d.outbox.entries.map { it.op }
        val full = checkNotNull(ContentPatch.of(SnippetType.VERSE, dev.nymph.lyrics.model.GridMode.AUTO, null, null, "mine"))
        assertEquals(
            listOf(
                SyncOp.EditSnippet(Fx.s1, Fx.c1, full, 7),
                SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 7),
                SyncOp.SnippetTag(Fx.s1, t2, false, 7),
                move,
            ),
            ops,
        )
        assertTrue(d.outbox.entries.none { it.isParked })
        assertEquals(7, d.snippet(Fx.s1)?.version)
    }

    @Test
    fun `overwrite of a delete deletes on the current version`() {
        val d = Fx.data().withoutSnippet(Fx.s1)
        val o = d.outbox.enqueue(SyncOp.DeleteSnippet(Fx.s1, 1))
        val r = d.withOutbox(o.park(o.entries[0].id, changed)).resolve(Resolution.OVERWRITE_WITH_MINE)
        assertEquals(listOf<SyncOp>(SyncOp.DeleteSnippet(Fx.s1, 7)), r.outbox.entries.map { it.op })
    }

    @Test
    fun `save mine as new creates a copy next to the original, which takes the server's`() {
        val d = parked(changed, edit("mine")).resolve(Resolution.SAVE_MINE_AS_NEW)
        val copyId = SnippetId("new1")
        assertEquals("mine", d.snippet(copyId)?.rawText)
        assertEquals("theirs", d.snippet(Fx.s1)?.rawText)
        val p = checkNotNull(d.placement(copyId, Fx.c1))
        assertEquals(10.0 + 300.0 + SAVE_AS_NEW_GAP, p.positionX)
        val ops = d.outbox.entries.map { it.op }
        assertTrue(ops[0] is SyncOp.CreateSnippet && (ops[0] as SyncOp.CreateSnippet).body.rawText == "mine")
        assertEquals(SyncOp.SnippetTag(copyId, Fx.t1, true, 1), ops[1])
        assertEquals(2, ops.size)
    }

    @Test
    fun `save mine as new for a snippet deleted on the server`() {
        val d = parked(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1)), edit("mine")).resolve(Resolution.SAVE_MINE_AS_NEW)
        assertNull(d.snippet(Fx.s1))
        assertEquals("mine", d.snippet(SnippetId("new1"))?.rawText)
    }

    @Test
    fun `retry merge rebases the changed originals and unparks`() {
        val merged = SnippetId("m")
        val merge = SyncOp.Merge(Fx.c1, merged, MergeMode.VERTICAL, listOf(SnippetVersion(Fx.s1, 1), SnippetVersion(Fx.s2, 1)))
        val d = parked(Conflict.MergeChanged(EntityRef.Snippet(merged), listOf(Fx.serverSnippet(Fx.s2, "c", 3))), merge).resolve(Resolution.RETRY_MERGE)
        assertEquals(OpState.Pending, d.outbox.entries.single().state)
        assertEquals(listOf(SnippetVersion(Fx.s1, 1), SnippetVersion(Fx.s2, 3)), (d.outbox.entries.single().op as SyncOp.Merge).sources)
    }

    @Test
    fun `review items show mine next to the server's`() {
        val d = parked(changed, edit("mine"))
        val item = reviewItems(d).single()
        assertEquals(ReviewSide.OfSnippet("mine", SnippetType.VERSE, listOf("Hook")), item.mine)
        assertEquals(ReviewSide.OfSnippet("theirs", SnippetType.HOOK, listOf("Two")), item.server)
        assertEquals("mine", item.label)
        assertEquals(LineDiff(setOf(1), emptySet()), lineDiff("a\nb", "a"))
    }
}
