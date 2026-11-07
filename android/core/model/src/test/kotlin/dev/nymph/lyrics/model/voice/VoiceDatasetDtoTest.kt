package dev.nymph.lyrics.model.voice

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceDatasetDtoTest {
    private fun json(text: String) = LyricsJson.parseToJsonElement(text)

    private val oldPayload = """{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg","duration_ms":2000,"byte_size":4,
        "transcript":"one\ntwo","split_mode":"taps","created_at":"2026-11-01T10:00:00Z"}"""

    private val fullPayload = """{"id":"r1","snippet_id":"s1","mime_type":"audio/ogg","duration_ms":2000,"byte_size":4,
        "transcript":"one two\nthree","split_mode":"taps","created_at":"2026-11-01T10:00:00Z",
        "lines":["one two","three"],"has_timings":true,
        "words":[{"text":"one","start_ms":0,"end_ms":300,"probability":0.9},
                 {"text":"two","start_ms":300,"end_ms":700,"probability":null},
                 {"text":"three","start_ms":900,"end_ms":1500}],
        "line_spans":[[0,2],[2,3]],"language":"en","initial_prompt":"Vocabulary: tin.",
        "verified_text":"one too\nthree","verified_at":"2026-11-02T08:00:00Z"}"""

    @Test
    fun `an old payload parses with every new field empty`() {
        val r = LyricsJson.decodeFromString(Recording.serializer(), oldPayload)
        assertEquals(emptyList<String>(), r.lines)
        assertFalse(r.hasTimings)
        assertNull(r.words)
        assertNull(r.lineSpans)
        assertNull(r.language)
        assertNull(r.initialPrompt)
        assertNull(r.verifiedText)
        assertNull(r.verifiedAt)
        assertNull(r.verification)
        assertFalse(r.isChecked)
    }

    @Test
    fun `reference lines fall back to the transcript when the server sent none`() {
        val r = LyricsJson.decodeFromString(Recording.serializer(), oldPayload)
        assertEquals(listOf("one", "two"), r.referenceLines)
    }

    @Test
    fun `a full payload parses every new field`() {
        val r = LyricsJson.decodeFromString(Recording.serializer(), fullPayload)
        assertEquals(listOf("one two", "three"), r.lines)
        assertEquals(listOf("one two", "three"), r.referenceLines)
        assertTrue(r.hasTimings)
        assertEquals(
            listOf(TimedWord("one", 0, 300, 0.9), TimedWord("two", 300, 700, null), TimedWord("three", 900, 1500, null)),
            r.words,
        )
        assertEquals(listOf(listOf(0, 2), listOf(2, 3)), r.lineSpans)
        assertEquals("en", r.language)
        assertEquals("Vocabulary: tin.", r.initialPrompt)
        assertEquals(Verification("one too\nthree", "2026-11-02T08:00:00Z"), r.verification)
        assertTrue(r.isChecked)
    }

    @Test
    fun `half a verification is no verification`() {
        val textOnly = LyricsJson.decodeFromString(
            Recording.serializer(),
            oldPayload.replace("\"split_mode\"", "\"verified_text\":\"x\",\"split_mode\""),
        )
        assertNull(textOnly.verification)
        val atOnly = LyricsJson.decodeFromString(
            Recording.serializer(),
            oldPayload.replace("\"split_mode\"", "\"verified_at\":\"2026-11-02T08:00:00Z\",\"split_mode\""),
        )
        assertNull(atOnly.verification)
    }

    @Test
    fun `the verification request encodes the text`() {
        assertEquals(
            json("""{"text":"a\nb"}"""),
            json(LyricsJson.encodeToString(VerificationRequest.serializer(), VerificationRequest("a\nb"))),
        )
    }

    @Test
    fun `stats decode the contract shape`() {
        val body = """{"total":15,"verified":12,"total_duration_ms":400000,"verified_duration_ms":250000,"with_timings":9}"""
        assertEquals(RecordingStats(15, 12, 400_000, 250_000, 9), LyricsJson.decodeFromString(RecordingStats.serializer(), body))
    }

    @Test
    fun `meta carries the vocabulary hints flag, on by default`() {
        val on = VoiceTakeMeta.of(emptyList(), 10, VoiceTakeTarget.Append(SnippetId("s1")))
        assertTrue(on.vocabularyHints)
        val off = VoiceTakeMeta.of(emptyList(), 10, VoiceTakeTarget.Append(SnippetId("s1")), vocabularyHints = false)
        assertEquals(
            json("""{"breaks_ms":[],"duration_ms":10,"target":{"kind":"append","snippet_id":"s1"},"vocabulary_hints":false}"""),
            json(LyricsJson.encodeToString(VoiceTakeMeta.serializer(), off)),
        )
    }

    @Test
    fun `the dataset error codes parse`() {
        assertEquals(VoiceErrorCode.INVALID_VERIFICATION, VoiceErrorCode.parse("invalid_verification"))
        assertEquals(VoiceErrorCode.EXPORT_TOO_LARGE, VoiceErrorCode.parse("export_too_large"))
        assertEquals(VoiceErrorCode.RECORDING_NOT_FOUND, VoiceErrorCode.parse("recording_not_found"))
    }
}
