package dev.nymph.lyrics.model

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TagDtoTest {
    private fun encoded(text: String) = LyricsJson.parseToJsonElement(text)

    @Test
    fun `user tag list decodes`() {
        val body = """[{"id":"t1","name":"love","color":"#6366f1","snippet_count":3,"created_at":"2026-11-01 10:00:00+00:00"},
            {"id":"t2","name":"Night","color":"#ff0000","snippet_count":0,"created_at":"2026-11-02"}]"""
        val tags = LyricsJson.decodeFromString(ListSerializer(UserTag.serializer()), body)
        assertEquals(UserTag(TagId("t1"), "love", "#6366f1", 3, "2026-11-01 10:00:00+00:00"), tags[0])
        assertEquals(Tag(TagId("t2"), "Night", "#ff0000"), tags[1].toTag())
    }

    @Test
    fun `embedded snippet tag ignores extra fields`() {
        val tag = LyricsJson.decodeFromString(Tag.serializer(), """{"id":"t1","name":"a","color":"#000000","snippet_count":0}""")
        assertEquals(Tag(TagId("t1"), "a", "#000000"), tag)
    }

    @Test
    fun `tag requests omit null fields`() {
        assertEquals(encoded("""{"name":"love"}"""), encoded(LyricsJson.encodeToString(TagCreateRequest.serializer(), TagCreateRequest("love"))))
        assertEquals(
            encoded("""{"name":"love","color":"#112233"}"""),
            encoded(LyricsJson.encodeToString(TagCreateRequest.serializer(), TagCreateRequest("love", "#112233"))),
        )
        assertEquals(encoded("""{"color":"#112233"}"""), encoded(LyricsJson.encodeToString(TagUpdateRequest.serializer(), TagUpdateRequest(color = "#112233"))))
    }

    @Test
    fun `presets decode and requests encode`() {
        val preset = LyricsJson.decodeFromString(
            TagPreset.serializer(),
            """{"id":"p1","name":"Dark","expression":"night & !love","created_at":"a","updated_at":"b"}""",
        )
        assertEquals(TagPreset(TagPresetId("p1"), "Dark", "night & !love", "a", "b"), preset)
        assertEquals(
            encoded("""{"name":"Dark","expression":"night"}"""),
            encoded(LyricsJson.encodeToString(TagPresetCreateRequest.serializer(), TagPresetCreateRequest("Dark", "night"))),
        )
        assertEquals(
            encoded("""{"expression":"night"}"""),
            encoded(LyricsJson.encodeToString(TagPresetUpdateRequest.serializer(), TagPresetUpdateRequest(expression = "night"))),
        )
    }

    @Test
    fun `rhyme request sends tag_filter only when set`() {
        val base = RhymeSuggestRequest(CanvasId("c1"), anchors = listOf(TextAnchorRef("door")))
        val plain = LyricsJson.parseToJsonElement(LyricsJson.encodeToString(RhymeSuggestRequest.serializer(), base))
        assertNull((plain as kotlinx.serialization.json.JsonObject)["tag_filter"])
        val filtered = LyricsJson.parseToJsonElement(LyricsJson.encodeToString(RhymeSuggestRequest.serializer(), base.copy(tagFilter = "love & night")))
        assertEquals("love & night", (filtered as kotlinx.serialization.json.JsonObject)["tag_filter"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `tag error codes parse`() {
        assertEquals(TagErrorCode.TAG_NAME_TAKEN, TagErrorCode.parse("tag_name_taken"))
        assertEquals(TagErrorCode.INVALID_PRESET_NAME, TagErrorCode.parse("invalid_preset_name"))
        assertNull(TagErrorCode.parse("nope"))
        assertNull(TagErrorCode.parse(null))
        assertEquals(9, TagErrorCode.entries.size)
    }

    @Test
    fun `expression errors are read from the detail`() {
        val body = """{"detail":{"error":"invalid_tag_expression","message":"bad","expression_error":{"code":"unexpected_end","position":6,"message":"end"}}}"""
        assertEquals(
            ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("unexpected_end", 6, "end")),
            ApiError.fromHttp(422, body),
        )
        assertNull(ApiError.expressionErrorFromBody("""{"detail":{"error":"x","expression_error":{"code":"c","position":"6"}}}"""))
        assertNull(ApiError.expressionErrorFromBody("""{"detail":"text"}"""))
    }

    @Test
    fun `conflicts keep their code`() {
        val e = ApiError.fromHttp(409, """{"detail":{"error":"tag_name_taken","message":"A tag named love exists"}}""")
        assertEquals(ApiError.Server(409, "A tag named love exists", "tag_name_taken"), e)
    }
}
