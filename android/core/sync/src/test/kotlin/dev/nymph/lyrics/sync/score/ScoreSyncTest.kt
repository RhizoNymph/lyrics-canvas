package dev.nymph.lyrics.sync.score

import dev.nymph.lyrics.model.SnapshotPlacement
import dev.nymph.lyrics.model.SnapshotSnippet
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.ScoreChange
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.apply.Edited
import dev.nymph.lyrics.sync.apply.INVALID_SCORE
import dev.nymph.lyrics.sync.conflict.Conflict
import dev.nymph.lyrics.sync.conflict.Resolution
import dev.nymph.lyrics.sync.conflict.Resolved
import dev.nymph.lyrics.sync.conflict.Resolver
import dev.nymph.lyrics.sync.conflict.ServerCopy
import dev.nymph.lyrics.sync.conflict.reviewItem
import dev.nymph.lyrics.sync.outbox.ContentPatch
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.score.ScoreFx.grid
import dev.nymph.lyrics.sync.score.ScoreFx.line
import dev.nymph.lyrics.sync.score.ScoreFx.n
import dev.nymph.lyrics.sync.score.ScoreFx.score
import dev.nymph.lyrics.sync.snapshot.SnapshotMerge
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.StoreJson
import dev.nymph.lyrics.sync.store.Views
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The score as offline content (round-4 contract sections 1 and 3): stored, synced, coalesced,
 * reviewed, and saved locally the way the server saves it.
 */
class ScoreSyncTest {
    private val edits = Fx.edits()
    private val threeWords = grid(line("I", "go", "now"))
    private val packed = score(listOf(n(0, 0), n(1, 24), n(2, 48)))

    /** s1 = "I go now" with [packed] (or [withScore]) and the staff view. */
    private fun data(withScore: Boolean = true, view: Boolean = true): LocalData {
        val d = Fx.data()
        val s1 = Fx.content(Fx.s1, "I go now").copy(
            gridData = threeWords,
            score = packed.takeIf { withScore },
            scoreView = view && withScore,
        )
        return d.withSnippet(s1)
    }

    private fun Edited<*>.ops() = data.outbox.entries.map { it.op }

    private fun Edited<*>.patch(): ContentPatch = (ops().single() as SyncOp.EditSnippet).patch

    private fun Edited<*>.error(): ApiError = (result as ApiResult.Failure).error

    @Nested
    inner class SaveFlow {
        @Test
        fun `a rest-only grid edit moves the later notes and is sent with the moved score`() {
            val newGrid = grid(line("I", "go", "_", "_", "now"))
            val e = edits.updateSnippet(data(), Fx.c1, Fx.s1, SnippetUpdateRequest(gridData = newGrid, rawText = "I go _ _ now"))
            val stored = e.data.snippet(Fx.s1)!!
            assertEquals(listOf(n(0, 0), n(1, 24), n(2, 144)), stored.score!!.lines.single().notes)
            assertEquals(newGrid, stored.gridData)
            assertEquals("I go _ _ now", stored.rawText)
            assertEquals(ScoreChange(stored.score), e.patch().score)
            assertEquals(newGrid, e.patch().gridData)
        }

        @Test
        fun `a text change reconciles the notes by word`() {
            val newGrid = grid(line("I", "really", "go", "now"))
            val e = edits.updateSnippet(data(), Fx.c1, Fx.s1, SnippetUpdateRequest(gridData = newGrid, rawText = "I really go now"))
            val stored = e.data.snippet(Fx.s1)!!
            assertEquals(listOf(n(0, 0), n(2, 24), n(3, 48)), stored.score!!.lines.single().notes)
            assertEquals("I really go now", stored.rawText)
            assertTrue(stored.scoreView)
        }

        @Test
        fun `a sent score writes its gaps into the grid and the text`() {
            val spaced = score(listOf(n(0, 0), n(1, 72), n(2, 96)))
            val e = edits.updateSnippet(data(withScore = false), Fx.c1, Fx.s1, SnippetUpdateRequest(score = ScoreChange.set(spaced), scoreView = true))
            val stored = e.data.snippet(Fx.s1)!!
            assertEquals(spaced, stored.score)
            assertTrue(stored.scoreView)
            assertEquals("I _ go now", stored.rawText)
            assertEquals(grid(line("I", "_", "go", "now")).lines.single().cells, stored.gridData!!.lines.single().cells)
            assertEquals("I _ go now", stored.gridData!!.lines.single().rawText)
            val patch = e.patch()
            assertEquals(ScoreChange(spaced), patch.score)
            assertEquals(stored.gridData, patch.gridData)
            assertEquals("I _ go now", patch.rawText)
            assertEquals(true, patch.scoreView)
        }

        @Test
        fun `a score that breaks its grid is refused like the server's 422 and changes nothing`() {
            val overlapping = score(listOf(n(0, 0, d = 48), n(1, 24)))
            val d = data(withScore = false)
            val e = edits.updateSnippet(d, Fx.c1, Fx.s1, SnippetUpdateRequest(score = ScoreChange.set(overlapping)))
            assertEquals(INVALID_SCORE, e.error().code)
            assertEquals(422, (e.error() as ApiError.Validation).status)
            assertTrue(e.data.outbox.isEmpty)
            assertEquals(d, e.data)
        }

        @Test
        fun `Remove score clears the score and the staff view and sends an explicit removal`() {
            val e = edits.updateSnippet(data(), Fx.c1, Fx.s1, SnippetUpdateRequest(score = ScoreChange.REMOVE))
            val stored = e.data.snippet(Fx.s1)!!
            assertNull(stored.score)
            assertFalse(stored.scoreView)
            assertEquals(ScoreChange.REMOVE, e.patch().score)
            assertEquals(threeWords, stored.gridData)
        }

        @Test
        fun `the staff view needs a score`() {
            val e = edits.updateSnippet(data(withScore = false), Fx.c1, Fx.s1, SnippetUpdateRequest(scoreView = true))
            assertFalse(e.data.snippet(Fx.s1)!!.scoreView)
            assertEquals(true, e.patch().scoreView)
        }

        @Test
        fun `toggling the view alone sends only the view`() {
            val e = edits.updateSnippet(data(), Fx.c1, Fx.s1, SnippetUpdateRequest(scoreView = false))
            assertFalse(e.data.snippet(Fx.s1)!!.scoreView)
            assertEquals(ContentPatch.of(scoreView = false), e.patch())
            assertEquals(packed, e.data.snippet(Fx.s1)!!.score)
        }

        @Test
        fun `text sent without a grid leaves the score to the server's reconcile`() {
            val e = edits.updateSnippet(data(), Fx.c1, Fx.s1, SnippetUpdateRequest(rawText = "I go now"))
            assertEquals(ContentPatch.of(rawText = "I go now"), e.patch())
            val stored = e.data.snippet(Fx.s1)!!.score!!
            assertEquals(3, stored.lines.single().notes.size, "the local estimate keeps the matching words")
        }

        @Test
        fun `a snippet without a score saves exactly as before`() {
            val e = edits.updateSnippet(data(withScore = false), Fx.c1, Fx.s1, SnippetUpdateRequest(gridData = threeWords, rawText = "I go now"))
            assertEquals(ContentPatch.of(gridData = threeWords, rawText = "I go now"), e.patch())
            assertNull(e.data.snippet(Fx.s1)!!.score)
        }

        @Test
        fun `a create keeps a valid score and refuses an invalid one`() {
            val ok = edits.createSnippet(
                Fx.data(), Fx.c1,
                SnippetCreateRequest(positionX = 0.0, positionY = 0.0, rawText = "I go now", gridData = threeWords, score = packed, scoreView = true),
            )
            val created = (ok.result as ApiResult.Success).value
            assertEquals(packed, created.score)
            assertTrue(created.scoreView)
            val bad = edits.createSnippet(
                Fx.data(), Fx.c1,
                SnippetCreateRequest(positionX = 0.0, positionY = 0.0, rawText = "I", gridData = grid(line("I")), score = packed),
            )
            assertEquals(INVALID_SCORE, bad.error().code)
        }
    }

    @Nested
    inner class Coalescing {
        private val set = checkNotNull(ContentPatch.of(gridData = threeWords, score = ScoreChange.set(packed)))

        @Test
        fun `later text without a grid drops an earlier score with the grid`() {
            val merged = set.then(checkNotNull(ContentPatch.of(rawText = "new")))
            assertNull(merged.gridData)
            assertNull(merged.score)
        }

        @Test
        fun `an earlier removal survives later text, and a later score wins`() {
            val removal = checkNotNull(ContentPatch.of(score = ScoreChange.REMOVE))
            assertEquals(ScoreChange.REMOVE, removal.then(checkNotNull(ContentPatch.of(rawText = "new"))).score)
            assertEquals(ScoreChange.set(packed), removal.then(set).score)
            assertEquals(ScoreChange.REMOVE, set.then(removal).score)
        }

        @Test
        fun `the view flag is last write wins`() {
            val a = checkNotNull(ContentPatch.of(scoreView = true))
            assertEquals(false, a.then(checkNotNull(ContentPatch.of(scoreView = false))).scoreView)
            assertEquals(true, a.then(checkNotNull(ContentPatch.of(rawText = "x"))).scoreView)
        }
    }

    @Nested
    inner class Storage {
        @Test
        fun `score, view and a queued removal survive the store file`() {
            val d = data().let { it.withOutbox(it.outbox.enqueue(SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(score = ScoreChange.REMOVE)), 1))) }
            val back = StoreJson.decodeFromString(LocalData.serializer(), StoreJson.encodeToString(LocalData.serializer(), d))
            assertEquals(d, back)
        }

        @Test
        fun `a store written before scores existed reads no score`() {
            val old = StoreJson.encodeToString(LocalData.serializer(), data(withScore = false))
                .replace(",\"score_view\":false", "")
            assertFalse("score" in old)
            val back = StoreJson.decodeFromString(LocalData.serializer(), old)
            assertNull(back.snippet(Fx.s1)!!.score)
            assertFalse(back.snippet(Fx.s1)!!.scoreView)
        }

        @Test
        fun `the views and the snapshot carry score and view`() {
            val view = Views.snippet(data(), Fx.s1, Fx.c1)!!
            assertEquals(packed, view.score)
            assertTrue(view.scoreView)
            val snapshot = SyncSnapshot(
                serverTime = Fx.T1,
                canvases = listOf(Fx.canvas(Fx.c1, "One")),
                placements = listOf(SnapshotPlacement(Fx.s1, Fx.c1, PlacementId("p"), 0.0, 0.0, 300.0, 200.0, 0)),
                snippets = listOf(
                    SnapshotSnippet(
                        Fx.s1, SnippetType.VERSE, GridMode.AUTO, null, threeWords, "I go now", null, null, Fx.T0, Fx.T1, Fx.T1, 2,
                        score = packed, scoreView = true,
                    ),
                ),
            )
            val merged = SnapshotMerge.merge(data(withScore = false), snapshot).snippet(Fx.s1)!!
            assertEquals(packed, merged.score)
            assertTrue(merged.scoreView)
        }

        @Test
        fun `a view flag without a score from the server reads as the grid view`() {
            val server = Fx.serverSnippet(Fx.s1, "x", 2).copy(scoreView = true)
            assertFalse(dev.nymph.lyrics.sync.store.LocalSnippet.of(server).scoreView)
        }
    }

    @Nested
    inner class Review {
        private val resolver = Resolver(newId = { "new" }, now = { Fx.T1 })

        private fun parkedWith(serverScore: dev.nymph.lyrics.model.score.Score?): LocalData {
            val d = data()
            val server = Fx.serverSnippet(Fx.s1, "I go now", 5).copy(gridData = threeWords, score = serverScore, scoreView = serverScore != null)
            val queued = d.outbox.enqueue(SyncOp.EditSnippet(Fx.s1, Fx.c1, checkNotNull(ContentPatch.of(scoreView = true)), 1))
            return d.withOutbox(queued.park(queued.entries[0].id, Conflict.Changed(EntityRef.Snippet(Fx.s1), ServerCopy.OfSnippet(server))))
        }

        @Test
        fun `different scores show as Cadence changed, equal ones do not`() {
            val changed = parkedWith(score(listOf(n(0, 0), n(1, 48), n(2, 96))))
            assertTrue(reviewItem(changed, changed.outbox.entries[0].id)!!.cadenceChanged)
            val none = parkedWith(null)
            assertTrue(reviewItem(none, none.outbox.entries[0].id)!!.cadenceChanged)
            val same = parkedWith(packed)
            assertFalse(reviewItem(same, same.outbox.entries[0].id)!!.cadenceChanged)
        }

        @Test
        fun `Overwrite with mine resends my score and view`() {
            val d = parkedWith(null)
            val done = resolver.resolve(d, d.outbox.entries[0].id, Resolution.OVERWRITE_WITH_MINE) as Resolved.Done
            val patch = (done.data.outbox.entries.first().op as SyncOp.EditSnippet).patch
            assertEquals(ScoreChange(packed), patch.score)
            assertEquals(true, patch.scoreView)
        }

        @Test
        fun `Save mine as new creates the copy with my score`() {
            val d = parkedWith(null)
            val done = resolver.resolve(d, d.outbox.entries[0].id, Resolution.SAVE_MINE_AS_NEW) as Resolved.Done
            val create = done.data.outbox.entries.map { it.op }.filterIsInstance<SyncOp.CreateSnippet>().single()
            assertEquals(packed, create.body.score)
            assertEquals(true, create.body.scoreView)
        }
    }
}
