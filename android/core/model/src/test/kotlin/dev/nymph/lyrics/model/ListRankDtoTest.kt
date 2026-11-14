package dev.nymph.lyrics.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The `list_rank` wire fields (contract section A). */
class ListRankDtoTest {
    private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T): JsonObject =
        LyricsJson.encodeToJsonElement(serializer, value).jsonObject

    private val snippetJson = """{"id":"s1","canvas_id":"c1","placement_id":"p1","snippet_type":"verse","grid_mode":"auto",
        "raw_text":"x","position_x":0,"position_y":0,"width":1,"height":1,"z_index":0,"created_at":"t","updated_at":"t",
        "content_updated_at":"t","canvases":[]LIST_RANK}"""

    @Test
    fun `a snippet decodes its placement's list_rank`() {
        val s = LyricsJson.decodeFromString(Snippet.serializer(), snippetJson.replace("LIST_RANK", ""","list_rank":"a0V""""))
        assertEquals("a0V", s.listRank)
    }

    @Test
    fun `a snippet without list_rank decodes the default key`() {
        assertEquals(DEFAULT_LIST_RANK, LyricsJson.decodeFromString(Snippet.serializer(), snippetJson.replace("LIST_RANK", "")).listRank)
        assertEquals("a0", DEFAULT_LIST_RANK)
    }

    @Test
    fun `a rank-only update sends list_rank and nothing else`() {
        assertEquals(JsonObject(mapOf("list_rank" to JsonPrimitive("a1"))), json(SnippetUpdateRequest.serializer(), SnippetUpdateRequest(listRank = "a1")))
    }

    @Test
    fun `a create sends list_rank only when chosen`() {
        assertFalse("list_rank" in json(SnippetCreateRequest.serializer(), SnippetCreateRequest(0.0, 0.0)))
        assertEquals(JsonPrimitive("Zz"), json(SnippetCreateRequest.serializer(), SnippetCreateRequest(0.0, 0.0, listRank = "Zz"))["list_rank"])
    }

    @Test
    fun `batch items may carry only a rank, or only a position`() {
        val body = json(
            BatchPositionRequest.serializer(),
            BatchPositionRequest(CanvasId("c1"), listOf(PositionUpdate(SnippetId("a"), listRank = "a0"), PositionUpdate(SnippetId("b"), 1.0, 2.0))),
        )
        assertEquals(
            """{"canvas_id":"c1","updates":[{"id":"a","list_rank":"a0"},{"id":"b","position_x":1.0,"position_y":2.0}]}""",
            body.toString(),
        )
    }

    @Test
    fun `invalid_list_rank is a placement error code`() {
        assertEquals(PlacementErrorCode.INVALID_LIST_RANK, PlacementErrorCode.parse("invalid_list_rank"))
    }
}
