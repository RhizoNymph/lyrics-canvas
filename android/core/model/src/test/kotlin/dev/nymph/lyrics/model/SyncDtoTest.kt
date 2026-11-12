package dev.nymph.lyrics.model

import dev.nymph.lyrics.model.error.ApiError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The offline-sync fields (docs/features/sync_versions.md): versions, base versions, client ids, the snapshot. */
class SyncDtoTest {
    @Test
    fun `responses carry version, defaulting to 1 when absent`() {
        assertEquals(1, LyricsJson.decodeFromString(Canvas.serializer(), Samples.CANVAS).version)
        val withVersion = Samples.SNIPPET.replace("\"raw_text\":\"hello world\",\"position_x\"", "\"version\":7,\"raw_text\":\"hello world\",\"position_x\"")
        assertEquals(7, LyricsJson.decodeFromString(Snippet.serializer(), withVersion).version)
        val tag = LyricsJson.decodeFromString(UserTag.serializer(), """{"id":"t","name":"n","color":"#000000","created_at":"x","version":3}""")
        assertEquals(3, tag.version)
        val word = LyricsJson.decodeFromString(BankWord.serializer(), """{"id":"w","text":"moon","created_at":"x","version":2}""")
        assertEquals(2, word.version)
    }

    @Test
    fun `base_version and client ids are sent only when set`() {
        val plain = LyricsJson.encodeToJsonElement(SnippetUpdateRequest.serializer(), SnippetUpdateRequest(rawText = "a")).jsonObject
        assertFalse("base_version" in plain)
        val based = LyricsJson.encodeToJsonElement(SnippetUpdateRequest.serializer(), SnippetUpdateRequest(rawText = "a", baseVersion = 4)).jsonObject
        assertEquals(JsonPrimitive(4), based["base_version"])
        val create = LyricsJson.encodeToJsonElement(CanvasCreateRequest.serializer(), CanvasCreateRequest("T", CanvasId("c-1"))).jsonObject
        assertEquals("c-1", create["id"]?.jsonPrimitive?.content)
        assertFalse("id" in LyricsJson.encodeToJsonElement(TagCreateRequest.serializer(), TagCreateRequest("n")).jsonObject)
        val tagPut = LyricsJson.encodeToJsonElement(TagUpdateRequest.serializer(), TagUpdateRequest(name = "x", baseVersion = 2)).jsonObject
        assertEquals(JsonPrimitive(2), tagPut["base_version"])
    }

    @Test
    fun `bulk word requests carry per-word base versions and add carries ids`() {
        val del = LyricsJson.encodeToJsonElement(WordBankDeleteRequest.serializer(), WordBankDeleteRequest(listOf(BankWordId("w")), mapOf("w" to 3))).jsonObject
        assertEquals(JsonPrimitive(3), del["base_versions"]?.jsonObject?.get("w"))
        val add = LyricsJson.encodeToJsonElement(WordBankSyncAddRequest.serializer(), WordBankSyncAddRequest(listOf(NewBankWord("moon", BankWordId("w1"))))).jsonObject
        val item = (add["words"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("w1", item["id"]?.jsonPrimitive?.content)
        assertEquals("moon", item["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a merge for sync names the merged id and the originals' versions`() {
        val request = checkNotNull(MergeRequest.of(listOf(SnippetId("a"), SnippetId("b")), MergeMode.VERTICAL))
        assertFalse("merged_id" in LyricsJson.encodeToJsonElement(MergeRequest.serializer(), request).jsonObject)
        val sync = LyricsJson.encodeToJsonElement(MergeRequest.serializer(), request.forSync(SnippetId("m"), mapOf(SnippetId("a") to 2, SnippetId("b") to 5))).jsonObject
        assertEquals("m", sync["merged_id"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(5), sync["base_versions"]?.jsonObject?.get("b"))
    }

    @Test
    fun `the snapshot decodes with snippets once and placements apart`() {
        val body = """{"server_time":"2026-11-01 10:00:00+00:00","canvases":[${Samples.CANVAS}],
            "placements":[{"snippet_id":"s","canvas_id":"c0ffee00-0000-4000-8000-000000000001","placement_id":"p","position_x":1,"position_y":2,
              "width":300,"height":200,"z_index":0,"group_id":null}],
            "snippets":[{"id":"s","snippet_type":"verse","grid_mode":"auto","raw_text":"hi","created_at":"a","updated_at":"b",
              "content_updated_at":"c","version":4,"tag_ids":["t"]}],
            "tags":[],"tag_presets":[],"word_bank":[]}"""
        val snap = LyricsJson.decodeFromString(SyncSnapshot.serializer(), body)
        assertEquals(4, snap.snippets.single().version)
        assertEquals(listOf(TagId("t")), snap.snippets.single().tagIds)
        assertEquals(1.0, snap.placements.single().positionX)
    }

    @Test
    fun `a version conflict keeps detail_current`() {
        val e = ApiError.fromHttp(409, """{"detail":{"error":"version_conflict","message":"changed","current":{"id":"x","version":3}}}""") as ApiError.Server
        assertEquals(SyncErrorCode.VERSION_CONFLICT, SyncErrorCode.parse(e.code))
        assertEquals(JsonPrimitive(3), e.current?.jsonObject?.get("version"))
        assertNull((ApiError.fromHttp(409, """{"detail":{"error":"tag_name_taken","message":"x"}}""") as ApiError.Server).current)
        assertTrue(ApiError.NeedsConnection.message.isNotBlank())
    }
}
