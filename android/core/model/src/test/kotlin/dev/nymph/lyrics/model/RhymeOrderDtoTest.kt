package dev.nymph.lyrics.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The meaning-order wire fields (contract section B). */
class RhymeOrderDtoTest {
    private fun <T> encoded(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        LyricsJson.encodeToJsonElement(serializer, value).jsonObject

    private val anchors = listOf(SnippetAnchorRef(SnippetId("a")))

    @Test
    fun `requests send order only for meaning`() {
        val rhyme = RhymeSuggestRequest(CanvasId("c1"), anchors)
        assertFalse("order" in encoded(RhymeSuggestRequest.serializer(), rhyme))
        assertEquals(JsonPrimitive("meaning"), encoded(RhymeSuggestRequest.serializer(), rhyme.copy(order = RhymeOrder.MEANING))["order"])

        val pattern = PatternSuggestRequest(CanvasId("c1"), SnippetId("s1"), 0, 2, listOf(SlotSpec("s0", listOf(SpanRef(0, 0, 1)))))
        assertFalse("order" in encoded(PatternSuggestRequest.serializer(), pattern))
        assertEquals(JsonPrimitive("meaning"), encoded(PatternSuggestRequest.serializer(), pattern.copy(order = RhymeOrder.MEANING))["order"])

        val bank = BankRhymesRequest.of(BankRhymeAnchor.Text("moon"))
        assertFalse("order" in encoded(BankRhymesRequest.serializer(), bank))
        val meaning = BankRhymesRequest.of(BankRhymeAnchor.Word(BankWordId("w1")), order = RhymeOrder.MEANING)
        assertEquals(JsonPrimitive("meaning"), encoded(BankRhymesRequest.serializer(), meaning)["order"])
        assertEquals(JsonPrimitive("w1"), encoded(BankRhymesRequest.serializer(), meaning)["word_id"])
    }

    private val candidateJson = """{"id":"dict:door","text":"door","provenance":"dictionary","ephemeral":true,"syllable_count":1,
        "stress":"1","rhyme_key":["AO1","R"],"syllables":[],"grid_line":{"cells":[],"syllable_count":0},"score":0.91,"match_depth":1,
        "best_anchor_id":"a","fits_slot":true,"alignment":[],"source":"dictionary"EXTRA}"""

    @Test
    fun `candidates decode band and similarity`() {
        val c = LyricsJson.decodeFromString(RhymeCandidate.serializer(), candidateJson.replace("EXTRA", ""","band":"strong","similarity":0.7312"""))
        assertEquals(RhymeBand.STRONG, c.band)
        assertEquals(0.7312, c.similarity)
        val plain = LyricsJson.decodeFromString(RhymeCandidate.serializer(), candidateJson.replace("EXTRA", ""","band":"near","similarity":null"""))
        assertEquals(RhymeBand.NEAR, plain.band)
        assertNull(plain.similarity)
    }

    @Test
    fun `every band decodes`() {
        mapOf("perfect" to RhymeBand.PERFECT, "strong" to RhymeBand.STRONG, "near" to RhymeBand.NEAR, "loose" to RhymeBand.LOOSE).forEach { (wire, band) ->
            assertEquals(band, LyricsJson.decodeFromString(RhymeBand.serializer(), "\"$wire\""))
        }
    }

    @Test
    fun `responses decode order_applied, rhyme when absent`() {
        val body = """{"anchors":[],"candidates":[],"total":0,"elapsed_ms":1.0,"order_applied":"meaning"}"""
        assertEquals(RhymeOrder.MEANING, LyricsJson.decodeFromString(RhymeSuggestResponse.serializer(), body).orderApplied)
        assertEquals(RhymeOrder.RHYME, LyricsJson.decodeFromString(RhymeSuggestResponse.serializer(), body.replace(""","order_applied":"meaning"""", "")).orderApplied)
        val pattern = """{"slots":[],"line_matches":[],"line_match_total":0,"elapsed_ms":1.0,"order_applied":"rhyme"}"""
        assertEquals(RhymeOrder.RHYME, LyricsJson.decodeFromString(PatternSuggestResponse.serializer(), pattern).orderApplied)
        val bank = """{"anchor":{"text":"moon"},"matches":[{"word":{"id":"w1","text":"spoon","tags":[],"created_at":"t"},"score":1.0,
            "match_depth":1,"band":"perfect","similarity":0.5}],"order_applied":"meaning"}"""
        val decoded = LyricsJson.decodeFromString(BankRhymesResponse.serializer(), bank)
        assertEquals(RhymeOrder.MEANING, decoded.orderApplied)
        assertEquals(RhymeBand.PERFECT, decoded.matches.single().band)
        assertEquals(0.5, decoded.matches.single().similarity)
    }
}
