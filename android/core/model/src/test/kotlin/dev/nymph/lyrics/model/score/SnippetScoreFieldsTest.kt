package dev.nymph.lyrics.model.score

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.SnapshotSnippet
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetUpdateRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The snippet `score` / `score_view` fields (round-4 contract section 1) on responses, snapshot and bodies. */
class SnippetScoreFieldsTest {
    private val scoreJson = """{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],
        "tempos":[{"bar":0,"bpm":90}],"snap":"1/16","lines":[{"notes":[{"s":0,"t":0,"d":12,"a":1}]}]}"""

    private val score = LyricsJson.decodeFromString(Score.serializer(), scoreJson)

    private fun snippetJson(extra: String) = """{"id":"s1","canvas_id":"c1","placement_id":"p1","snippet_type":"verse",
        "grid_mode":"auto","raw_text":"hi","position_x":0,"position_y":0,"width":300,"height":200,"z_index":0,
        "created_at":"t","updated_at":"t","content_updated_at":"t","canvases":[]$extra}"""

    @Test
    fun `a snippet without the fields has no score and the grid view`() {
        val s = LyricsJson.decodeFromString(Snippet.serializer(), snippetJson(""))
        assertNull(s.score)
        assertFalse(s.scoreView)
    }

    @Test
    fun `a snippet response carries score and score_view`() {
        val s = LyricsJson.decodeFromString(Snippet.serializer(), snippetJson(""","score":$scoreJson,"score_view":true"""))
        assertEquals(score, s.score)
        assertTrue(s.scoreView)
    }

    @Test
    fun `an explicit null score decodes as no score`() {
        val s = LyricsJson.decodeFromString(Snippet.serializer(), snippetJson(""","score":null,"score_view":false"""))
        assertNull(s.score)
    }

    @Test
    fun `the snapshot snippet carries both`() {
        val text = """{"id":"s1","snippet_type":"verse","grid_mode":"auto","raw_text":"hi","created_at":"t","updated_at":"t",
            "content_updated_at":"t","score":$scoreJson,"score_view":true}"""
        val s = LyricsJson.decodeFromString(SnapshotSnippet.serializer(), text)
        assertEquals(score, s.score)
        assertTrue(s.scoreView)
    }

    private fun encode(body: SnippetUpdateRequest): JsonObject = LyricsJson.encodeToJsonElement(SnippetUpdateRequest.serializer(), body).jsonObject

    @Test
    fun `an update without a score change sends neither field`() {
        val out = encode(SnippetUpdateRequest(rawText = "x"))
        assertFalse("score" in out)
        assertFalse("score_view" in out)
    }

    @Test
    fun `Remove score is an explicit null`() {
        val out = encode(SnippetUpdateRequest(score = ScoreChange.REMOVE))
        assertEquals(JsonNull, out["score"])
    }

    @Test
    fun `setting a score sends the score object and the view flag`() {
        val out = encode(SnippetUpdateRequest(score = ScoreChange.set(score), scoreView = true))
        assertEquals(score, LyricsJson.decodeFromJsonElement(Score.serializer(), out.getValue("score")))
        assertEquals(JsonPrimitive(true), out["score_view"])
    }

    @Test
    fun `a score change round-trips through its own (store) serializer`() {
        for (change in listOf(ScoreChange.REMOVE, ScoreChange.set(score))) {
            val text = LyricsJson.encodeToString(ScoreChange.serializer(), change)
            assertEquals(change, LyricsJson.decodeFromString(ScoreChange.serializer(), text))
        }
    }

    @Test
    fun `a create body carries the score when given`() {
        val out = LyricsJson.encodeToJsonElement(
            SnippetCreateRequest.serializer(),
            SnippetCreateRequest(positionX = 0.0, positionY = 0.0, score = score, scoreView = true),
        ).jsonObject
        assertEquals(score, LyricsJson.decodeFromJsonElement(Score.serializer(), out.getValue("score")))
        assertEquals(JsonPrimitive(true), out["score_view"])
        val bare = LyricsJson.encodeToJsonElement(SnippetCreateRequest.serializer(), SnippetCreateRequest(positionX = 0.0, positionY = 0.0)).jsonObject
        assertFalse("score" in bare)
        assertFalse("score_view" in bare)
    }
}
