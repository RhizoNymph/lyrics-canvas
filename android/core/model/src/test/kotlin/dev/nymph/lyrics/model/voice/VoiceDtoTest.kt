package dev.nymph.lyrics.model.voice

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VoiceDtoTest {
    private fun json(text: String) = LyricsJson.parseToJsonElement(text)

    @Test
    fun `meta for a new snippet encodes the contract shape`() {
        val meta = VoiceTakeMeta.of(listOf(1_200, 400), 5_000, VoiceTakeTarget.New(12.5, -3.0))
        assertEquals(
            json("""{"breaks_ms":[400,1200],"duration_ms":5000,"target":{"kind":"new","position_x":12.5,"position_y":-3.0},"vocabulary_hints":true}"""),
            json(LyricsJson.encodeToString(VoiceTakeMeta.serializer(), meta)),
        )
    }

    @Test
    fun `meta for an append encodes the snippet id`() {
        val meta = VoiceTakeMeta.of(emptyList(), 800, VoiceTakeTarget.Append(SnippetId("s1")))
        assertEquals(
            json("""{"breaks_ms":[],"duration_ms":800,"target":{"kind":"append","snippet_id":"s1"},"vocabulary_hints":true}"""),
            json(LyricsJson.encodeToString(VoiceTakeMeta.serializer(), meta)),
        )
    }

    @Test
    fun `meta drops breaks outside the take and duplicates`() {
        val meta = VoiceTakeMeta.of(listOf(-5, 0, 300, 300, 1_000, 1_001), 1_000, VoiceTakeTarget.New(0.0, 0.0))
        assertEquals(listOf(0L, 300L, 1_000L), meta.breaksMs)
        assertEquals(0L, VoiceTakeMeta.of(listOf(10), -1, VoiceTakeTarget.New(0.0, 0.0)).durationMs)
    }

    @Test
    fun `decodes a recording list`() {
        val body = """[{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg;codecs=opus","duration_ms":61234,"byte_size":40960,
            "transcript":"hello\nworld","split_mode":"auto","created_at":"2026-11-01T10:00:00Z","extra":1}]"""
        val list = LyricsJson.decodeFromString(ListSerializer(Recording.serializer()), body)
        assertEquals(
            listOf(Recording(RecordingId("r1"), SnippetId("s1"), "audio/ogg;codecs=opus", 61_234, 40_960, "hello\nworld", SplitMode.AUTO, "2026-11-01T10:00:00Z")),
            list,
        )
    }

    @Test
    fun `audio mime parsing ignores parameters and case`() {
        assertEquals(AudioMime.OGG, AudioMime.parse("audio/ogg;codecs=opus"))
        assertEquals(AudioMime.MP4, AudioMime.parse("Audio/MP4"))
        assertNull(AudioMime.parse("audio/webm"))
    }

    @Test
    fun `error codes parse from the wire`() {
        assertEquals(VoiceErrorCode.STT_DISABLED, VoiceErrorCode.parse("stt_disabled"))
        assertNull(VoiceErrorCode.parse("nope"))
        assertNull(VoiceErrorCode.parse(null))
        VoiceErrorCode.entries.forEach { assertEquals(it, VoiceErrorCode.parse(it.wire)) }
    }

    @Test
    fun `structured error bodies give the code and the message`() {
        val body = """{"detail":{"error":"unsupported_audio_type","message":"audio/flac is not accepted"}}"""
        assertEquals(ApiError.Server(415, "audio/flac is not accepted", "unsupported_audio_type"), ApiError.fromHttp(415, body))
        assertEquals("unsupported_audio_type", ApiError.fromHttp(415, body).code)
        assertEquals(
            ApiError.Validation(422, "no words", "empty_transcript"),
            ApiError.fromHttp(422, """{"detail":{"error":"empty_transcript","message":"no words"}}"""),
        )
        assertEquals(ApiError.NotFound("gone", "canvas_not_found"), ApiError.fromHttp(404, """{"detail":{"error":"canvas_not_found","message":"gone"}}"""))
    }

    @Test
    fun `plain bodies carry no code`() {
        assertNull(ApiError.fromHttp(404, """{"detail":"Canvas not found"}""").code)
        assertNull(ApiError.fromHttp(500, "oops").code)
        assertNull(ApiError.Unauthorized(401, null).code)
        assertNull(ApiError.errorCodeFromBody("""{"detail":{"error":3}}"""))
        assertNull(ApiError.detailFromBody("""{"detail":{"error":"x"}}"""))
        assertEquals("x", ApiError.errorCodeFromBody("""{"detail":{"error":"x"}}"""))
    }
}
