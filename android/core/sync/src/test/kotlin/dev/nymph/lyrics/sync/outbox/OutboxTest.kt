package dev.nymph.lyrics.sync.outbox

import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.sync.conflict.Conflict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OutboxTest {
    private val c1 = CanvasId("c1")
    private val c2 = CanvasId("c2")
    private val s1 = SnippetId("s1")
    private val s2 = SnippetId("s2")
    private val t1 = TagId("t1")

    private fun text(t: String) = checkNotNull(ContentPatch.of(rawText = t))
    private fun at(x: Double, y: Double) = checkNotNull(LayoutPatch.of(positionX = x, positionY = y))
    private fun edit(id: SnippetId, t: String, base: Int = 1) = SyncOp.EditSnippet(id, c1, text(t), base)
    private fun move(id: SnippetId, x: Double, canvas: CanvasId = c1) = SyncOp.MoveSnippet(id, canvas, at(x, 0.0))
    private fun outbox(vararg ops: SyncOp) = ops.fold(Outbox.EMPTY) { o, op -> o.enqueue(op) }
    private fun Outbox.ops() = entries.map { it.op }

    @Nested
    inner class Coalescing {
        @Test
        fun `consecutive content edits of one snippet become one edit keeping the first base`() {
            val o = outbox(edit(s1, "a", base = 3), edit(s1, "b", base = 4))
            assertEquals(listOf(SyncOp.EditSnippet(s1, c1, text("b"), 3)), o.ops())
        }

        @Test
        fun `later fields win and earlier ones are kept`() {
            val first = SyncOp.EditSnippet(s1, c1, checkNotNull(ContentPatch.of(snippetType = SnippetType.CHORUS)), 1)
            val o = outbox(first, edit(s1, "b"))
            assertEquals(ContentPatch.of(snippetType = SnippetType.CHORUS, rawText = "b"), (o.ops().single() as SyncOp.EditSnippet).patch)
        }

        @Test
        fun `text without a grid drops an earlier grid so the server re-syllabifies`() {
            val withGrid = SyncOp.EditSnippet(s1, c1, checkNotNull(ContentPatch.of(rawText = "a", gridData = GridData())), 1)
            val o = outbox(withGrid, edit(s1, "b"))
            assertEquals(ContentPatch.of(rawText = "b"), (o.ops().single() as SyncOp.EditSnippet).patch)
        }

        @Test
        fun `edits of different snippets stay separate`() {
            assertEquals(2, outbox(edit(s1, "a"), edit(s2, "b")).entries.size)
        }

        @Test
        fun `an edit skips layout updates of the same snippet`() {
            val o = outbox(edit(s1, "a"), move(s1, 5.0), edit(s1, "b"))
            assertEquals(listOf(SyncOp.EditSnippet(s1, c1, text("b"), 1), move(s1, 5.0)), o.ops())
        }

        @Test
        fun `never across a create`() {
            val create = SyncOp.CreateSnippet(s1, c1, SnippetCreateRequest(0.0, 0.0))
            assertEquals(2, outbox(create, edit(s1, "a")).entries.size)
            assertEquals(3, outbox(edit(s1, "a"), create, edit(s1, "b")).entries.size)
        }

        @Test
        fun `never across a delete or a tag link`() {
            assertEquals(3, outbox(edit(s1, "a"), SyncOp.SnippetTag(s1, t1, true, 1), edit(s1, "b")).entries.size)
            assertEquals(3, outbox(move(s1, 1.0), SyncOp.RemoveFromCanvas(s1, c1, 1), move(s1, 2.0)).entries.size)
        }

        @Test
        fun `layout updates per snippet and canvas keep the last one`() {
            val o = outbox(move(s1, 1.0), move(s1, 2.0, c2), move(s1, 3.0))
            assertEquals(listOf(move(s1, 3.0), move(s1, 2.0, c2)), o.ops())
        }

        @Test
        fun `layout skips content edits and tag links of the same snippet`() {
            val o = outbox(move(s1, 1.0), edit(s1, "a"), SyncOp.SnippetTag(s1, t1, true, 1), move(s1, 2.0))
            assertEquals(move(s1, 2.0), o.ops().first())
            assertEquals(3, o.entries.size)
        }

        @Test
        fun `in-flight and failed operations are never changed`() {
            val inFlight = outbox(edit(s1, "a")).let { it.markInFlight(setOf(it.entries[0].id)) }.enqueue(edit(s1, "b"))
            assertEquals(2, inFlight.entries.size)
            val failed = outbox(move(s1, 1.0)).let { it.fail(setOf(it.entries[0].id), "down") }.enqueue(move(s1, 2.0))
            assertEquals(2, failed.entries.size)
        }

        @Test
        fun `an edit of a parked snippet updates the parked change`() {
            val parked = outbox(edit(s1, "a")).let { it.park(it.entries[0].id, Conflict.DeletedOnServer(EntityRef.Snippet(s1))) }
            val o = parked.enqueue(edit(s1, "b"))
            assertEquals(1, o.entries.size)
            assertTrue(o.entries[0].isParked)
            assertEquals(text("b"), (o.entries[0].op as SyncOp.EditSnippet).patch)
        }

        @Test
        fun `viewport keeps the last value and title edits merge`() {
            val o = outbox(
                SyncOp.SaveViewport(c1, ViewportState(1.0)),
                SyncOp.RenameCanvas(c1, "A", 2),
                SyncOp.SaveViewport(c1, ViewportState(2.0)),
                SyncOp.RenameCanvas(c1, "B", 3),
            )
            assertEquals(listOf(SyncOp.SaveViewport(c1, ViewportState(2.0)), SyncOp.RenameCanvas(c1, "B", 2)), o.ops())
        }

        @Test
        fun `tag updates merge field by field and word renames keep the last text`() {
            val o = outbox(SyncOp.UpdateTag(t1, name = "a", baseVersion = 1), SyncOp.UpdateTag(t1, color = "#000000", baseVersion = 2))
            assertEquals(listOf(SyncOp.UpdateTag(t1, "a", "#000000", 1)), o.ops())
            val w = BankWordId("w")
            assertEquals(listOf(SyncOp.RenameWord(w, "z", 1)), outbox(SyncOp.RenameWord(w, "y", 1), SyncOp.RenameWord(w, "z", 2)).ops())
        }

        @Test
        fun `ids keep increasing`() {
            val o = outbox(edit(s1, "a"), edit(s2, "b"))
            val after = o.complete(setOf(o.entries[1].id)).enqueue(edit(s2, "c"))
            assertEquals(listOf(1L, 3L), after.entries.map { it.id.value })
        }
    }

    @Nested
    inner class Scheduling {
        @Test
        fun `sends in order`() {
            val o = outbox(edit(s1, "a"), edit(s2, "b"))
            assertEquals(o.entries[0], o.nextBatch()?.first)
        }

        @Test
        fun `an operation waits for an earlier one on the same entity`() {
            val o = outbox(edit(s1, "a"), move(s1, 2.0)).let { it.markInFlight(setOf(it.entries[0].id)) }
            assertNull(o.nextBatch())
        }

        @Test
        fun `a parked conflict blocks its entity only`() {
            val o = outbox(edit(s1, "a"), move(s1, 1.0), edit(s2, "b")).let { it.park(it.entries[0].id, Conflict.DeletedOnServer(EntityRef.Snippet(s1))) }
            assertEquals(SyncOp.EditSnippet(s2, c1, text("b"), 1), o.nextBatch()?.first?.op)
        }

        @Test
        fun `a create runs before anything that references the created entity`() {
            val createCanvas = SyncOp.CreateCanvas(c2, "New")
            val o = outbox(createCanvas, SyncOp.CreateSnippet(s1, c2, SnippetCreateRequest(0.0, 0.0))).let { it.markInFlight(setOf(it.entries[0].id)) }
            assertNull(o.nextBatch())
            val tagFirst = outbox(SyncOp.CreateTag(t1, "x"), SyncOp.SnippetTag(s1, t1, true, 1)).let { it.markInFlight(setOf(it.entries[0].id)) }
            assertNull(tagFirst.nextBatch())
        }

        @Test
        fun `creates on a canvas with a parked title conflict still go`() {
            val o = outbox(SyncOp.RenameCanvas(c1, "T", 1), SyncOp.CreateSnippet(s1, c1, SnippetCreateRequest(0.0, 0.0)))
                .let { it.park(it.entries[0].id, Conflict.DeletedOnServer(EntityRef.Canvas(c1))) }
            assertTrue(o.nextBatch()?.first?.op is SyncOp.CreateSnippet)
        }

        @Test
        fun `a delete waits for earlier operations naming what it deletes`() {
            val o = outbox(SyncOp.CreateSnippet(s1, c1, SnippetCreateRequest(0.0, 0.0)), SyncOp.DeleteCanvas(c1, 1))
                .let { it.markInFlight(setOf(it.entries[0].id)) }
            assertNull(o.nextBatch())
        }

        @Test
        fun `failed operations are sent again in place`() {
            val o = outbox(edit(s1, "a"), edit(s2, "b")).let { it.fail(setOf(it.entries[0].id), "timeout") }
            assertEquals(o.entries[0].id, o.nextBatch()?.first?.id)
            assertEquals(OpState.Failed(1, "timeout"), o.entries[0].state)
            assertEquals(OpState.Failed(2, "again"), o.fail(setOf(o.entries[0].id), "again").entries[0].state)
        }

        @Test
        fun `word operations of one kind are batched, others are not`() {
            val w = (1..3).map { BankWordId("w$it") }
            val o = outbox(
                SyncOp.DeleteWord(w[0], 1),
                SyncOp.WordTag(w[2], t1, TagLinkOp.LINK, 1),
                SyncOp.DeleteWord(w[1], 1),
            )
            assertEquals(listOf(SyncOp.DeleteWord(w[0], 1), SyncOp.DeleteWord(w[1], 1)), o.nextBatch()?.entries?.map { it.op })
        }

        @Test
        fun `a word operation that must wait is not batched`() {
            val w = BankWordId("w1")
            val o = outbox(SyncOp.DeleteWord(BankWordId("w0"), 1), SyncOp.RenameWord(w, "x", 1), SyncOp.DeleteWord(w, 1))
            assertEquals(1, o.nextBatch()?.entries?.size)
        }

        @Test
        fun `adds batch only with the same tags`() {
            val o = outbox(SyncOp.AddWord(BankWordId("a"), "a"), SyncOp.AddWord(BankWordId("b"), "b", listOf(t1)), SyncOp.AddWord(BankWordId("c"), "c"))
            assertEquals(listOf("a", "c"), o.nextBatch()?.entries?.map { (it.op as SyncOp.AddWord).text })
        }
    }

    @Nested
    inner class Bookkeeping {
        @Test
        fun `rebase moves later operations to the new version`() {
            val o = outbox(edit(s1, "a", 3), SyncOp.SnippetTag(s1, t1, true, 3), edit(s2, "b", 7))
            val done = o.complete(setOf(o.entries[0].id)).rebase(EntityRef.Snippet(s1), 4)
            assertEquals(listOf(SyncOp.SnippetTag(s1, t1, true, 4), edit(s2, "b", 7)), done.ops())
        }

        @Test
        fun `rebase updates a merge's source version`() {
            val merge = SyncOp.Merge(c1, SnippetId("m"), MergeMode.VERTICAL, listOf(SnippetVersion(s1, 1), SnippetVersion(s2, 5)))
            val o = outbox(merge).rebase(EntityRef.Snippet(s1), 2)
            assertEquals(listOf(SnippetVersion(s1, 2), SnippetVersion(s2, 5)), (o.ops().single() as SyncOp.Merge).sources)
        }

        @Test
        fun `remap rewrites tag and word ids`() {
            val local = TagId("local")
            val o = outbox(SyncOp.SnippetTag(s1, local, true, 1), SyncOp.WordTag(BankWordId("w"), local, TagLinkOp.LINK, 1))
                .remap(IdRemap(tags = mapOf(local to t1), words = mapOf(BankWordId("w") to BankWordId("server"))))
            assertEquals(listOf(SyncOp.SnippetTag(s1, t1, true, 1), SyncOp.WordTag(BankWordId("server"), t1, TagLinkOp.LINK, 1)), o.ops())
        }

        @Test
        fun `recovered puts in-flight operations back to pending`() {
            val o = outbox(edit(s1, "a")).let { it.markInFlight(setOf(it.entries[0].id)) }.recovered()
            assertEquals(OpState.Pending, o.entries[0].state)
        }

        @Test
        fun `counts and lookups`() {
            val o = outbox(edit(s1, "a"), edit(s2, "b")).let { it.park(it.entries[0].id, Conflict.DeletedOnServer(EntityRef.Snippet(s1))) }
            assertEquals(1, o.unsentCount)
            assertEquals(1, o.parked.size)
            assertEquals(o.entries[0], o.parkedOn(EntityRef.Snippet(s1)))
            assertNull(o.parkedOn(EntityRef.Snippet(s2)))
            assertTrue(o.hasOpsOn(EntityRef.Snippet(s2)))
            assertFalse(o.hasOpsOn(EntityRef.Canvas(c1)))
        }

        @Test
        fun `insertAfter places new operations right behind one`() {
            val o = outbox(edit(s1, "a"), edit(s2, "b"))
            val added = o.insertAfter(o.entries[0].id, listOf(move(s1, 9.0)))
            assertEquals(listOf(edit(s1, "a"), move(s1, 9.0), edit(s2, "b")), added.ops())
            assertEquals(3L, added.entries[1].id.value)
        }
    }
}
