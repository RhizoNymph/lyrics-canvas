package dev.nymph.lyrics.model

import dev.nymph.lyrics.model.error.ApiError
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlacementDtoTest {
    private fun json(text: String) = LyricsJson.parseToJsonElement(text)

    @Test
    fun `snippet carries its placement id and every canvas holding it`() {
        val s = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET)
        assertEquals(PlacementId("91ace000-0000-4000-8000-000000000001"), s.placementId)
        assertEquals(
            listOf(
                CanvasRef(CanvasId("c0ffee00-0000-4000-8000-000000000002"), "Chorus ideas"),
                CanvasRef(CanvasId("c0ffee00-0000-4000-8000-000000000001"), "Night drive"),
            ),
            s.canvases,
        )
        val bare = LyricsJson.decodeFromString<Snippet>(Samples.SNIPPET_BARE)
        assertEquals(listOf(bare.canvasId), bare.canvases.map { it.id })
    }

    @Test
    fun `a snippet without placement_id is not a placement-era snippet`() {
        val old = Samples.SNIPPET_BARE.replace(""""placement_id":"91ace000-0000-4000-8000-000000000002",""", "")
        assertThrows<SerializationException> { LyricsJson.decodeFromString<Snippet>(old) }
    }

    @Test
    fun `transfer request de-duplicates and encodes the wire names`() {
        val req = TransferRequest.of(listOf(SnippetId("a"), SnippetId("b"), SnippetId("a")), CanvasId("t"), TransferMode.SHOW)!!
        assertEquals(listOf(SnippetId("a"), SnippetId("b")), req.snippetIds)
        assertEquals(
            json("""{"snippet_ids":["a","b"],"target_canvas_id":"t","mode":"show"}"""),
            json(LyricsJson.encodeToString(TransferRequest.serializer(), req)),
        )
        val move = TransferRequest.of(listOf(SnippetId("a")), CanvasId("t"), TransferMode.MOVE)!!
        assertEquals(json("""{"snippet_ids":["a"],"target_canvas_id":"t","mode":"move"}"""), json(LyricsJson.encodeToString(TransferRequest.serializer(), move)))
    }

    @Test
    fun `transfer request refuses an empty or oversized batch`() {
        assertNull(TransferRequest.of(emptyList(), CanvasId("t"), TransferMode.MOVE))
        val max = (1..TransferRequest.MAX_SNIPPETS).map { SnippetId("s$it") }
        assertEquals(500, TransferRequest.of(max, CanvasId("t"), TransferMode.MOVE)!!.snippetIds.size)
        assertNull(TransferRequest.of(max + SnippetId("one-more"), CanvasId("t"), TransferMode.MOVE))
    }

    @Test
    fun `transfer response`() {
        val r = LyricsJson.decodeFromString<TransferResponse>(Samples.TRANSFER)
        assertEquals(CanvasId("c0ffee00-0000-4000-8000-000000000002"), r.targetCanvasId)
        assertEquals(listOf(SnippetId("5a1e0000-0000-4000-8000-000000000001")), r.placed)
        assertEquals(listOf(SnippetId("5a1e0000-0000-4000-8000-000000000002")), r.alreadyOnTarget)
        assertTrue(r.removedFromSource)
    }

    @Test
    fun `remove placement response`() {
        assertFalse(LyricsJson.decodeFromString<RemovePlacementResponse>(Samples.REMOVE_PLACEMENT).snippetDeleted)
        assertTrue(LyricsJson.decodeFromString<RemovePlacementResponse>("""{"snippet_deleted":true}""").snippetDeleted)
    }

    @Test
    fun `placement error codes parse from structured bodies`() {
        val cases = mapOf(
            "invalid_canvas_title" to PlacementErrorCode.INVALID_CANVAS_TITLE,
            "placement_not_found" to PlacementErrorCode.PLACEMENT_NOT_FOUND,
            "canvas_not_found" to PlacementErrorCode.CANVAS_NOT_FOUND,
            "same_canvas" to PlacementErrorCode.SAME_CANVAS,
        )
        for ((wire, code) in cases) {
            val error = ApiError.fromHttp(if (code.wire.endsWith("not_found")) 404 else 422, """{"detail":{"error":"$wire","message":"m"}}""")
            assertEquals(code, PlacementErrorCode.parse(error.code))
            assertEquals("m", error.message)
        }
        assertNull(PlacementErrorCode.parse("tag_not_found"))
        assertNull(PlacementErrorCode.parse(null))
    }
}
