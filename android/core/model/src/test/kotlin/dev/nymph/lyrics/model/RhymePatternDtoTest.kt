package dev.nymph.lyrics.model

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RhymePatternDtoTest {
    private fun encoded(text: String) = LyricsJson.parseToJsonElement(text)

    private val span = """{"line_index":0,"word_start":1,"word_end":3,"text":"the weather","syllable_count":3,"stress":"010",
        "rhyme_key":["EH","DH","ER"],"syllables":[{"onset":["W"],"nucleus":"EH","coda":[],"stress":1}],"extra":true}"""

    @Test
    fun `detect request encodes the range with the default threshold and omits nulls`() {
        val request = PatternDetectRequest(CanvasId("c1"), SnippetId("s1"), 2, 5)
        assertEquals(
            encoded("""{"canvas_id":"c1","snippet_id":"s1","line_start":2,"line_end":5,"min_score":0.75}"""),
            encoded(LyricsJson.encodeToString(PatternDetectRequest.serializer(), request)),
        )
    }

    @Test
    fun `suggest request encodes every field with the contract defaults`() {
        val request = PatternSuggestRequest(CanvasId("c1"), SnippetId("s1"), 0, 2, listOf(SlotSpec(members = listOf(SpanRef(0, 1, 2)))))
        assertEquals(
            encoded(
                """{"canvas_id":"c1","snippet_id":"s1","line_start":0,"line_end":2,"slots":[{"members":[{"line_index":0,"word_start":1,"word_end":2}]}],
                "min_score":0.5,"sources":["notes","dictionary"],"include_rare":false,"exclude_ids":[],"limit":30,"offsets":{},
                "line_matches":true,"line_match_limit":30,"line_match_offset":0}""",
            ),
            encoded(LyricsJson.encodeToString(PatternSuggestRequest.serializer(), request)),
        )
    }

    @Test
    fun `slots decode with k as an enum and unknown fields ignored`() {
        val slot = LyricsJson.decodeFromString(
            PatternSlot.serializer(),
            """{"id":"s1","members":[$span],"coverage":1,"full":false,"default_on":false,"k":3,"cohesion":1.0,"syllable_count":3,"stress":"010","new":1}""",
        )
        assertEquals(RhymeK.K3, slot.k)
        assertEquals(SpanRef(0, 1, 3), slot.members.single().ref)
        assertEquals(listOf("EH", "DH", "ER"), slot.members.single().rhymeKey)
    }

    @Test
    fun `a slot k outside 1 to 4 is a decoding failure`() {
        assertThrows<SerializationException> {
            LyricsJson.decodeFromString(
                PatternSlot.serializer(),
                """{"id":"s1","members":[$span],"coverage":1,"full":false,"default_on":false,"k":5,"cohesion":1.0,"syllable_count":3,"stress":"010"}""",
            )
        }
    }

    @Test
    fun `an empty detect response decodes`() {
        val response = LyricsJson.decodeFromString(PatternDetectResponse.serializer(), """{"lines":[],"slots":[],"elapsed_ms":0.4}""")
        assertEquals(PatternDetectResponse(emptyList(), emptyList(), 0.4), response)
    }

    @Test
    fun `error codes parse from their wire names`() {
        assertEquals(PatternErrorCode.RANGE_INVALID, PatternErrorCode.parse("pattern_range_invalid"))
        assertEquals(PatternErrorCode.SPAN_INVALID, PatternErrorCode.parse("pattern_span_invalid"))
        assertEquals(PatternErrorCode.SLOT_ORDER, PatternErrorCode.parse("pattern_slot_order"))
        assertEquals(PatternErrorCode.INVALID_TAG_EXPRESSION, PatternErrorCode.parse("invalid_tag_expression"))
        assertNull(PatternErrorCode.parse("version_conflict"))
        assertNull(PatternErrorCode.parse(null))
    }
}
