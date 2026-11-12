package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.conflict.ServerCopy
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SnippetVersion
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class OutcomesTest {
    private fun edit(text: String, base: Int = 1, id: SnippetId = Fx.s1) = SyncOp.EditSnippet(id, Fx.c1, checkNotNull(ContentPatch.of(rawText = text)), base)

    private fun LocalData.queue(vararg ops: SyncOp) = withOutbox(ops.fold(outbox) { o, op -> o.enqueue(op) })

    /** Send the next batch and apply [outcome]. */
    private fun LocalData.answer(outcome: SendOutcome): Applied {
        val batch = checkNotNull(outbox.nextBatch())
        return Outcomes.apply(withOutbox(outbox.markInFlight(batch.ids)), batch, outcome)
    }

    private fun json(s: Snippet) = LyricsJson.encodeToJsonElement(Snippet.serializer(), s)

    @Test
    fun `success with nothing else queued takes the server's copy`() {
        val d = Fx.data().queue(edit("mine"))
        val a = d.answer(SendOutcome.Ok(OpResult.SnippetSaved(Fx.serverSnippet(Fx.s1, "mine", 2))))
        assertTrue(a.data.outbox.isEmpty)
        assertEquals(2, a.data.snippet(Fx.s1)?.version)
        assertEquals(1.0, a.data.placement(Fx.s1, Fx.c1)?.positionX, "the response placement for its canvas")
        assertEquals(0.0, a.data.placement(Fx.s1, Fx.c2)?.positionX)
    }

    @Test
    fun `success with more queued keeps the local overlay, moves the version and rebases`() {
        val d = Fx.data().queue(edit("a"), SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1)).withSnippet(Fx.content(Fx.s1, "local newest"))
        val a = d.answer(SendOutcome.Ok(OpResult.SnippetSaved(Fx.serverSnippet(Fx.s1, "a", 5))))
        assertEquals("local newest", a.data.snippet(Fx.s1)?.rawText)
        assertEquals(5, a.data.snippet(Fx.s1)?.version)
        assertEquals(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 5), a.data.outbox.entries.single().op)
    }

    @Test
    fun `a layout answer never rebases queued content changes (it would hide a conflict)`() {
        val move = SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(dev.nymph.lyrics.sync.outbox.LayoutPatch.of(positionX = 3.0)))
        val d = Fx.data().queue(move, edit("mine", base = 1))
        val a = d.answer(SendOutcome.Ok(OpResult.SnippetSaved(Fx.serverSnippet(Fx.s1, "changed elsewhere", 2))))
        assertEquals(edit("mine", base = 1), a.data.outbox.entries.single().op)
        assertEquals(1, a.data.snippet(Fx.s1)?.version)
    }

    @Test
    fun `a create's answer never re-creates something deleted meanwhile`() {
        val created = SnippetId("n")
        val d = Fx.data().queue(SyncOp.CreateSnippet(created, Fx.c1, SnippetCreateRequest(0.0, 0.0)))
        val a = d.answer(SendOutcome.Ok(OpResult.SnippetSaved(Fx.serverSnippet(created, "", 1))))
        assertNull(a.data.snippet(created))
    }

    @Test
    fun `a tag link answer moves the snippet version`() {
        val d = Fx.data().queue(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1), edit("x"))
        val a = d.answer(SendOutcome.Ok(OpResult.SnippetVersioned(Fx.s1, 2)))
        assertEquals(2, a.data.snippet(Fx.s1)?.version)
        assertEquals(edit("x", base = 2), a.data.outbox.entries.single().op)
    }

    @Test
    fun `409 parks the change with the server's copy and raises one alert`() {
        val d = Fx.data().queue(edit("mine"), edit("other", id = Fx.s2))
        val server = Fx.serverSnippet(Fx.s1, "theirs", 3)
        val a = d.answer(SendOutcome.VersionConflict(json(server)))
        val parked = a.data.outbox.entries[0].state as OpState.Parked
        assertEquals(Conflict.Changed(EntityRef.Snippet(Fx.s1), ServerCopy.OfSnippet(server)), parked.conflict)
        assertEquals(1, a.alerts.size)
        assertEquals("Change to \"first line\" isn't synced: it was also changed on the server", a.alerts[0].message)
        assertEquals(SyncOp.EditSnippet(Fx.s2, Fx.c1, checkNotNull(ContentPatch.of(rawText = "other")), 1), a.data.outbox.nextBatch()?.first?.op, "other entities continue")
    }

    @Test
    fun `a conflict with my own lost-answer retry is a success`() {
        val d = Fx.data().queue(edit("mine"))
        val a = d.answer(SendOutcome.VersionConflict(json(Fx.serverSnippet(Fx.s1, "mine", 2))))
        assertTrue(a.data.outbox.isEmpty)
        assertTrue(a.alerts.isEmpty())
        assertEquals(2, a.data.snippet(Fx.s1)?.version)
    }

    @Test
    fun `an unreadable current parks as rejected rather than losing the change`() {
        val a = Fx.data().queue(edit("mine")).answer(SendOutcome.VersionConflict(null))
        assertTrue((a.data.outbox.entries.single().state as OpState.Parked).conflict is Conflict.Rejected)
    }

    @Test
    fun `404 on a delete is done, 404 on an edit is deleted on the server`() {
        assertTrue(Fx.data().queue(SyncOp.DeleteSnippet(Fx.s1, 1)).answer(SendOutcome.Ok(OpResult.Done)).data.outbox.isEmpty)
        val a = Fx.data().queue(edit("x")).answer(SendOutcome.NotFound(null))
        assertEquals(Conflict.DeletedOnServer(EntityRef.Snippet(Fx.s1)), (a.data.outbox.entries.single().state as OpState.Parked).conflict)
    }

    @Test
    fun `a link to a tag deleted on the server is dropped with the tag`() {
        val a = Fx.data().queue(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1)).answer(SendOutcome.NotFound("tag_not_found"))
        assertTrue(a.data.outbox.isEmpty)
        assertNull(a.data.tag(Fx.t1))
        assertTrue(a.refresh)
    }

    @Test
    fun `transient failures keep the change in place, counting attempts`() {
        val a = Fx.data().queue(edit("x")).answer(SendOutcome.Transient(ApiError.Network(IOException("down"))))
        assertTrue(a.transient)
        assertEquals(OpState.Failed(1, "down"), a.data.outbox.entries.single().state)
    }

    @Test
    fun `an unreadable success is done and asks for a snapshot`() {
        val a = Fx.data().queue(edit("x")).answer(SendOutcome.Unreadable(ApiError.Decoding(IOException("bad"))))
        assertTrue(a.data.outbox.isEmpty)
        assertTrue(a.refresh)
    }

    @Test
    fun `a refused change parks as rejected with the server's message`() {
        val a = Fx.data().queue(SyncOp.RenameCanvas(Fx.c1, "", 1)).answer(SendOutcome.Refused(ApiError.Validation(422, "Title is empty")))
        assertEquals(Conflict.Rejected(EntityRef.Canvas(Fx.c1), "Title is empty"), (a.data.outbox.entries.single().state as OpState.Parked).conflict)
    }

    @Test
    fun `a merge conflict parks the whole merge on the merged snippet`() {
        val merged = SnippetId("m")
        val d = Fx.data().queue(SyncOp.Merge(Fx.c1, merged, MergeMode.VERTICAL, listOf(SnippetVersion(Fx.s1, 1), SnippetVersion(Fx.s2, 1))))
        val changed = Fx.serverSnippet(Fx.s2, "changed", 4)
        val a = d.answer(SendOutcome.VersionConflict(LyricsJson.encodeToJsonElement(ListSerializer(Snippet.serializer()), listOf(changed))))
        assertEquals(Conflict.MergeChanged(EntityRef.Snippet(merged), listOf(changed)), (a.data.outbox.entries.single().state as OpState.Parked).conflict)
    }

    @Test
    fun `a word batch conflict parks only the conflicting words, the rest go again`() {
        val w2 = BankWordId("w2")
        val d = Fx.data().withWord(Fx.word(w2, "sun")).queue(SyncOp.DeleteWord(Fx.w1, 1), SyncOp.DeleteWord(w2, 1))
        val current = LyricsJson.encodeToJsonElement(ListSerializer(BankWord.serializer()), listOf(Fx.serverWord(w2, "sun!", 2)))
        val a = d.answer(SendOutcome.VersionConflict(current))
        assertEquals(OpState.Pending, a.data.outbox.entries[0].state)
        assertTrue(a.data.outbox.entries[1].isParked)
        assertEquals(1, a.alerts.size)
    }

    @Test
    fun `an added word the server already had takes the server's id everywhere`() {
        val local = BankWordId("local")
        val server = BankWordId("server")
        val d = Fx.data().withWord(Fx.word(local, "Sun")).queue(SyncOp.AddWord(local, "Sun"), SyncOp.WordTag(local, Fx.t1, TagLinkOp.LINK, 1))
        val a = d.answer(SendOutcome.Ok(OpResult.WordsAdded(WordBankAddResponse(existing = listOf(Fx.serverWord(server, "sun", 3))))))
        assertNull(a.data.word(local))
        assertEquals(3, a.data.word(server)?.version)
        assertEquals(SyncOp.WordTag(server, Fx.t1, TagLinkOp.LINK, 1), a.data.outbox.entries.single().op)
    }

    @Test
    fun `a tag created on another device meanwhile becomes the server's tag`() {
        val local = TagId("local")
        val server = Fx.serverTag(TagId("srv"), "Bridge", 1)
        val d = Fx.data().withTag(Fx.tag(local, "Bridge")).withSnippet(Fx.content(Fx.s1, "x", tags = listOf(local)))
            .queue(SyncOp.CreateTag(local, "Bridge"), SyncOp.SnippetTag(Fx.s1, local, true, 1))
        val a = d.answer(SendOutcome.Ok(OpResult.TagMatched(local, server)))
        assertNull(a.data.tag(local))
        assertEquals(listOf(server.id), a.data.snippet(Fx.s1)?.tagIds)
        assertEquals(SyncOp.SnippetTag(Fx.s1, server.id, true, 1), a.data.outbox.entries.single().op)
    }

    @Test
    fun `a tag conflict decodes the server's tag`() {
        val server = Fx.serverTag(Fx.t1, "Renamed elsewhere", 2)
        val d = Fx.data().queue(SyncOp.UpdateTag(Fx.t1, name = "Mine", baseVersion = 1))
        val a = d.answer(SendOutcome.VersionConflict(LyricsJson.encodeToJsonElement(dev.nymph.lyrics.model.UserTag.serializer(), server)))
        assertEquals(Conflict.Changed(EntityRef.Tag(Fx.t1), ServerCopy.OfTag(server)), (a.data.outbox.entries.single().state as OpState.Parked).conflict)
    }

    @Test
    fun `a self-conflict check covers tag links`() {
        val d = Fx.data().queue(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1))
        val a = d.answer(SendOutcome.VersionConflict(json(Fx.serverSnippet(Fx.s1, "x", 2, tags = listOf(Tag(Fx.t1, "Hook", "#112233"))))))
        assertTrue(a.data.outbox.isEmpty)
    }
}
