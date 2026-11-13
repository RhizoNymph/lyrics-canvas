package dev.nymph.lyrics.sync.apply

import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.WordBankErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.LayoutPatch
import dev.nymph.lyrics.sync.outbox.SnippetVersion
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.Views
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LocalEditsTest {
    private val edits = Fx.edits()
    private val d = Fx.data()

    private fun <T> Edited<T>.value(): T = (result as ApiResult.Success).value
    private fun <T> Edited<T>.error(): ApiError = (result as ApiResult.Failure).error
    private fun Edited<*>.ops() = data.outbox.entries.map { it.op }

    @Nested
    inner class Canvases {
        @Test
        fun `create adds the canvas with a client id and queues the create`() {
            val e = edits.createCanvas(d, "New")
            assertEquals(CanvasId("id1"), e.value().id)
            assertEquals(Fx.user, e.value().ownerId)
            assertEquals(listOf(SyncOp.CreateCanvas(CanvasId("id1"), "New")), e.ops())
            assertEquals("New", Views.canvasList(e.data).first().title)
        }

        @Test
        fun `rename is content based on the canvas version and renames the snippets' canvas refs`() {
            val e = edits.renameCanvas(d.withCanvas(Fx.canvas(Fx.c1, "One", version = 4)), Fx.c1, "Uno")
            assertEquals(listOf(SyncOp.RenameCanvas(Fx.c1, "Uno", 4)), e.ops())
            assertEquals("Uno", Views.snippet(e.data, Fx.s1, Fx.c2)?.canvases?.first { it.id == Fx.c1 }?.title)
        }

        @Test
        fun `viewport is layout and a missing canvas is canvas_not_found`() {
            val e = edits.saveViewport(d, Fx.c1, ViewportState(1.0, 2.0, 3.0))
            assertEquals(listOf(SyncOp.SaveViewport(Fx.c1, ViewportState(1.0, 2.0, 3.0))), e.ops())
            assertEquals("canvas_not_found", edits.saveViewport(d, CanvasId("x"), ViewportState()).error().code)
        }

        @Test
        fun `delete removes the canvas and snippets only on it`() {
            val e = edits.deleteCanvas(d, Fx.c1)
            assertNull(e.data.canvas(Fx.c1))
            assertNull(e.data.snippet(Fx.s2))
            assertNotNull(e.data.snippet(Fx.s1), "s1 is still on c2")
            assertEquals(listOf(SyncOp.DeleteCanvas(Fx.c1, 1)), e.ops())
        }
    }

    @Nested
    inner class Snippets {
        @Test
        fun `create places it with defaults and a local grid estimate`() {
            val e = edits.createSnippet(d, Fx.c1, SnippetCreateRequest(5.0, 6.0, rawText = "hello world"))
            val s = e.value()
            assertEquals(SnippetId("id1"), s.id)
            assertEquals(SnippetType.FREESTYLE, s.snippetType)
            assertEquals(300.0, s.width)
            assertEquals(1, s.gridData?.lines?.size)
            assertEquals(listOf(SyncOp.CreateSnippet(SnippetId("id1"), Fx.c1, SnippetCreateRequest(5.0, 6.0, rawText = "hello world"))), e.ops())
        }

        @Test
        fun `create on a missing canvas fails and changes nothing`() {
            val e = edits.createSnippet(d, CanvasId("x"), SnippetCreateRequest(0.0, 0.0))
            assertSame(d, e.data)
            assertTrue(e.error() is ApiError.NotFound)
        }

        @Test
        fun `update splits content and layout`() {
            val body = SnippetUpdateRequest(rawText = "new", snippetType = SnippetType.HOOK, positionX = 9.0)
            val e = edits.updateSnippet(d.withSnippet(Fx.content(Fx.s1, "x", version = 3)), Fx.c1, Fx.s1, body)
            assertEquals(
                listOf(
                    SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(snippetType = SnippetType.HOOK, rawText = "new")), 3),
                    SyncOp.MoveSnippet(Fx.s1, Fx.c1, checkNotNull(LayoutPatch.of(positionX = 9.0))),
                ),
                e.ops(),
            )
            assertEquals("new", e.value().rawText)
            assertEquals(9.0, e.value().positionX)
            assertEquals(Fx.T1, e.value().contentUpdatedAt)
            assertEquals(0.0, Views.snippet(e.data, Fx.s1, Fx.c2)?.positionX, "the other canvas keeps its layout")
        }

        @Test
        fun `remove from canvas keeps a snippet placed elsewhere and deletes the last copy`() {
            val shared = edits.removeFromCanvas(d, Fx.c1, Fx.s1)
            assertEquals(false, shared.value().snippetDeleted)
            assertNotNull(shared.data.snippet(Fx.s1))
            val last = edits.removeFromCanvas(d, Fx.c1, Fx.s2)
            assertEquals(true, last.value().snippetDeleted)
            assertNull(last.data.snippet(Fx.s2))
            assertEquals(listOf(SyncOp.RemoveFromCanvas(Fx.s2, Fx.c1, 1)), last.ops())
        }

        @Test
        fun `delete everywhere and batch position`() {
            val del = edits.deleteEverywhere(d, Fx.s1)
            assertTrue(del.data.placementsOf(Fx.s1).isEmpty())
            val moved = edits.batchPosition(d, Fx.c1, listOf(PositionUpdate(Fx.s1, 7.0, 8.0), PositionUpdate(SnippetId("nope"), 0.0, 0.0)))
            assertEquals(1, moved.value().updated)
            assertEquals(7.0, moved.data.placement(Fx.s1, Fx.c1)?.positionX)
        }

        @Test
        fun `merge replaces the originals here and keeps one placed elsewhere`() {
            val e = edits.merge(d, Fx.c1, checkNotNull(MergeRequest.of(listOf(Fx.s2, Fx.s1), MergeMode.HORIZONTAL)))
            val merged = e.value().snippet
            assertEquals("first line other\nsecond", merged.rawText)
            assertEquals(listOf(Fx.s1, Fx.s2), e.value().deletedIds)
            assertEquals(listOf(Fx.t1), merged.tags.map { it.id })
            assertNull(e.data.placement(Fx.s1, Fx.c1))
            assertNotNull(e.data.placement(Fx.s1, Fx.c2))
            assertNull(e.data.snippet(Fx.s2))
            assertEquals(listOf(SyncOp.Merge(Fx.c1, merged.id, MergeMode.HORIZONTAL, listOf(SnippetVersion(Fx.s2, 1), SnippetVersion(Fx.s1, 1)))), e.ops())
        }

        @Test
        fun `transfer stacks new placements above the target and a move removes the sources`() {
            val req = checkNotNull(TransferRequest.of(listOf(Fx.s2, Fx.s1), Fx.c2, TransferMode.MOVE))
            val e = edits.transfer(d, Fx.c1, req)
            assertEquals(listOf(Fx.s2), e.value().placed)
            assertEquals(listOf(Fx.s1), e.value().alreadyOnTarget)
            assertEquals(1, e.data.placement(Fx.s2, Fx.c2)?.zIndex)
            assertNull(e.data.placement(Fx.s2, Fx.c1))
            assertEquals("same_canvas", edits.transfer(d, Fx.c2, req).error().code)
        }
    }

    @Nested
    inner class Tags {
        @Test
        fun `create uses the default colour and refuses a taken name like the server`() {
            val e = edits.createTag(d, "Verse", null)
            assertEquals(DEFAULT_TAG_COLOR, e.value().color)
            val taken = edits.createTag(d, "hook", null).error()
            assertEquals(409, (taken as ApiError.Server).status)
            assertEquals(TagErrorCode.TAG_NAME_TAKEN.wire, taken.code)
        }

        @Test
        fun `update renames everywhere and delete unlinks`() {
            val e = edits.updateTag(d, Fx.t1, TagUpdateRequest(name = "Chorus"))
            assertEquals("Chorus", Views.snippet(e.data, Fx.s2, Fx.c1)?.tags?.single()?.name)
            assertEquals(listOf(SyncOp.UpdateTag(Fx.t1, "Chorus", null, 1)), e.ops())
            val del = edits.deleteTag(d, Fx.t1)
            assertTrue(Views.snippet(del.data, Fx.s2, Fx.c1)?.tags.orEmpty().isEmpty())
        }

        @Test
        fun `link and unlink are snippet content, a no-op sends nothing`() {
            val e = edits.setSnippetTag(d, Fx.s1, Fx.t1, link = true)
            assertEquals(listOf(SyncOp.SnippetTag(Fx.s1, Fx.t1, true, 1)), e.ops())
            assertSame(d, edits.setSnippetTag(d, Fx.s2, Fx.t1, link = true).data)
            assertEquals("tag_not_found", edits.setSnippetTag(d, Fx.s1, TagId("x"), link = true).error().code)
        }
    }

    @Nested
    inner class Words {
        @Test
        fun `add creates new words and links tags to existing ones`() {
            val e = edits.addWords(d, listOf("Moon", "june"), listOf(Fx.t1))
            assertEquals(listOf("june"), e.value().created.map { it.text })
            assertEquals(listOf("moon"), e.value().existing.map { it.text })
            assertEquals(
                listOf(SyncOp.WordTag(Fx.w1, Fx.t1, TagLinkOp.LINK, 1), SyncOp.AddWord(BankWordId("id1"), "june", listOf(Fx.t1))),
                e.ops(),
            )
        }

        @Test
        fun `rename refuses another word's text and delete ignores unknown ids`() {
            val withTwo = d.withWord(Fx.word(BankWordId("w2"), "sun"))
            assertEquals(WordBankErrorCode.WORD_EXISTS.wire, edits.renameWord(withTwo, Fx.w1, "SUN").error().code)
            val del = edits.deleteWords(withTwo, listOf(Fx.w1, BankWordId("zz")))
            assertEquals(listOf(SyncOp.DeleteWord(Fx.w1, 1)), del.ops())
        }

        @Test
        fun `set tag changes only words that change`() {
            val tagged = d.withWord(Fx.word(Fx.w1, "moon", tags = listOf(Fx.t1))).withWord(Fx.word(BankWordId("w2"), "sun"))
            val e = edits.setWordTag(tagged, listOf(Fx.w1, BankWordId("w2")), Fx.t1, TagLinkOp.LINK)
            assertEquals(listOf(SyncOp.WordTag(BankWordId("w2"), Fx.t1, TagLinkOp.LINK, 1)), e.ops())
            assertEquals(2, e.value().size)
        }
    }
}
