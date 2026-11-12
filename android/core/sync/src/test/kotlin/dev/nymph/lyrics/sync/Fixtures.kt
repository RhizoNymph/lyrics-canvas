package dev.nymph.lyrics.sync

import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.UserId
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.store.LocalData
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalTag
import dev.nymph.lyrics.sync.store.LocalWord

/** Sample data for the sync tests: two canvases, two snippets (s1 on both), one tag, one word. */
object Fx {
    val user = UserId("u1")
    val c1 = CanvasId("c1")
    val c2 = CanvasId("c2")
    val s1 = SnippetId("s1")
    val s2 = SnippetId("s2")
    val t1 = TagId("t1")
    val w1 = BankWordId("w1")
    const val T0 = "2026-11-01T10:00:00Z"
    const val T1 = "2026-11-02T10:00:00Z"

    fun canvas(id: CanvasId, title: String, version: Int = 1, updatedAt: String = T0) =
        Canvas(id, user, title, false, null, null, T0, updatedAt, version)

    fun content(id: SnippetId, text: String, version: Int = 1, tags: List<TagId> = emptyList(), contentUpdatedAt: String = T0) =
        LocalSnippet(id, SnippetType.VERSE, GridMode.AUTO, null, null, text, tags, null, null, T0, T0, contentUpdatedAt, version)

    fun placement(id: SnippetId, canvas: CanvasId, x: Double = 0.0, y: Double = 0.0, z: Int = 0) =
        LocalPlacement(id, canvas, PlacementId("p-$id-$canvas"), x, y, 300.0, 200.0, z, null)

    fun tag(id: TagId, name: String, version: Int = 1) = LocalTag(id, name, "#112233", T0, version)

    fun word(id: BankWordId, text: String, version: Int = 1, tags: List<TagId> = emptyList()) = LocalWord(id, text, tags, T0, version)

    /** s1 on c1 and c2, s2 on c1 (tagged t1), word w1. */
    fun data(): LocalData = LocalData(
        account = user,
        canvases = listOf(canvas(c1, "One"), canvas(c2, "Two")),
        snippets = listOf(content(s1, "first line\nsecond"), content(s2, "other", tags = listOf(t1))),
        placements = listOf(placement(s1, c1, 10.0, 20.0), placement(s1, c2), placement(s2, c1, 500.0, 0.0, z = 1)),
        tags = listOf(tag(t1, "Hook")),
        words = listOf(word(w1, "moon")),
        snapshotAt = T0,
    )

    /** A server snippet response in [canvas]'s context. */
    fun serverSnippet(
        id: SnippetId,
        text: String,
        version: Int,
        canvas: CanvasId = c1,
        tags: List<Tag> = emptyList(),
        type: SnippetType = SnippetType.VERSE,
    ) = Snippet(
        id = id,
        canvasId = canvas,
        placementId = PlacementId("srv-$id"),
        snippetType = type,
        gridMode = GridMode.AUTO,
        rawText = text,
        positionX = 1.0,
        positionY = 2.0,
        width = 300.0,
        height = 200.0,
        zIndex = 0,
        tags = tags,
        createdAt = T0,
        updatedAt = T1,
        contentUpdatedAt = T1,
        canvases = listOf(CanvasRef(canvas, "One")),
        version = version,
    )

    fun serverTag(id: TagId, name: String, version: Int) = UserTag(id, name, "#112233", 0, T0, 0, version)

    fun serverWord(id: BankWordId, text: String, version: Int, tags: List<Tag> = emptyList()) = BankWord(id, text, tags, T0, version)

    /** Deterministic ids `id1`, `id2`, ... and a fixed clock. */
    fun edits(): LocalEdits {
        var n = 0
        return LocalEdits(now = { T1 }, newId = { "id${++n}" })
    }
}
