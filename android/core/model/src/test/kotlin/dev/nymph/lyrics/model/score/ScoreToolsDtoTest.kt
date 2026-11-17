package dev.nymph.lyrics.model.score

import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.RhythmUnavailableReason
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Draft / export wire (contract section 5) and the rhyme rhythm-fit additions (section 6). */
class ScoreToolsDtoTest {
    private fun parse(text: String) = LyricsJson.parseToJsonElement(text)

    @Test
    fun `a grid draft request has source, note, meter and bpm`() {
        val body = ScoreDraftRequest.FromGrid(ScoreSnap.EIGHTH, DraftMeter(3, 4), 92.5).toJson()
        assertEquals(parse("""{"source":"grid","note":"1/8","meter":{"num":3,"den":4},"bpm":92.5}"""), body)
    }

    @Test
    fun `every snap encodes as its wire name in a grid draft`() {
        val wires = mapOf(
            ScoreSnap.EIGHTH to "1/8",
            ScoreSnap.SIXTEENTH to "1/16",
            ScoreSnap.THIRTY_SECOND to "1/32",
            ScoreSnap.EIGHTH_TRIPLET to "1/8t",
            ScoreSnap.SIXTEENTH_TRIPLET to "1/16t",
        )
        wires.forEach { (snap, wire) ->
            assertEquals(JsonPrimitive(wire), ScoreDraftRequest.FromGrid(snap, DraftMeter(4, 4), 90.0).toJson()["note"])
        }
    }

    @Test
    fun `a recording draft sends bpm null explicitly to ask for an estimate`() {
        val body = ScoreDraftRequest.FromRecording(RecordingId("r1"), DraftMeter(6, 8), null).toJson()
        assertEquals(parse("""{"source":"recording","recording_id":"r1","meter":{"num":6,"den":8},"bpm":null}"""), body)
        assertEquals(JsonNull, body["bpm"])
    }

    @Test
    fun `a recording draft with a tempo sends it`() {
        val body = ScoreDraftRequest.FromRecording(RecordingId("r1"), DraftMeter(4, 4), 120.0).toJson()
        assertEquals(JsonPrimitive(120.0), body["bpm"])
    }

    @Test
    fun `the draft response decodes score, estimate and counts`() {
        val text = """{"score":{"v":1,"ppq":48,"pickup":12,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":101.5}],
            "snap":"1/16","lines":[{"notes":[{"s":0,"t":0,"d":12,"a":1}]}]},"bpm_estimated":101.5,"unplaced":2,"unmatched_words":1}"""
        val r = LyricsJson.decodeFromString(ScoreDraftResponse.serializer(), text)
        assertEquals(12, r.score.pickup)
        assertEquals(101.5, r.bpmEstimated)
        assertEquals(2, r.unplaced)
        assertEquals(1, r.unmatchedWords)
    }

    @Test
    fun `a grid draft response has no estimate`() {
        val text = """{"score":{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":90}],
            "snap":"1/8","lines":[]},"bpm_estimated":null,"unplaced":0,"unmatched_words":0}"""
        assertNull(LyricsJson.decodeFromString(ScoreDraftResponse.serializer(), text).bpmEstimated)
    }

    @Test
    fun `export formats carry query value, extension and mime`() {
        assertEquals(listOf("midi", "musicxml"), ScoreExportFormat.entries.map { it.wire })
        assertEquals("mid", ScoreExportFormat.MIDI.extension)
        assertEquals("audio/midi", ScoreExportFormat.MIDI.mime)
        assertEquals("musicxml", ScoreExportFormat.MUSICXML.extension)
        assertEquals("application/vnd.recordare.musicxml+xml", ScoreExportFormat.MUSICXML.mime)
    }

    @Test
    fun `every tool error code parses and unknown codes are null`() {
        val codes = listOf("snippet_not_found", "score_not_found", "recording_not_found", "recording_has_no_timings", "draft_empty", "invalid_score")
        assertEquals(codes, ScoreToolErrorCode.entries.map { it.wire })
        codes.forEach { assertEquals(it, ScoreToolErrorCode.parse(it)?.wire) }
        assertNull(ScoreToolErrorCode.parse("nope"))
        assertNull(ScoreToolErrorCode.parse(null))
    }

    // ---- rhyme-suggest rhythm fit ----

    private val anchors = listOf(SnippetAnchorRef(SnippetId("a")))

    @Test
    fun `rhythm_fit is sent only when on`() {
        val off = LyricsJson.encodeToJsonElement(RhymeSuggestRequest.serializer(), RhymeSuggestRequest(CanvasId("c1"), anchors)).jsonObject
        assertFalse("rhythm_fit" in off)
        val on = LyricsJson.encodeToJsonElement(RhymeSuggestRequest.serializer(), RhymeSuggestRequest(CanvasId("c1"), anchors, rhythmFit = true)).jsonObject
        assertEquals(JsonPrimitive(true), on["rhythm_fit"])
    }

    private val candidateJson = """{"id":"dict:door","text":"door","provenance":"dictionary","ephemeral":true,"syllable_count":1,
        "stress":"1","rhyme_key":["AO1","R"],"syllables":[],"grid_line":{"cells":[],"syllable_count":0},"score":0.91,"match_depth":1,
        "best_anchor_id":"a","fits_slot":true,"alignment":[],"source":"dictionary"EXTRA}"""

    @Test
    fun `candidates decode rhythm_fit, null when absent`() {
        val fit = LyricsJson.decodeFromString(RhymeCandidate.serializer(), candidateJson.replace("EXTRA", ""","rhythm_fit":0.75"""))
        assertEquals(0.75, fit.rhythmFit)
        val none = LyricsJson.decodeFromString(RhymeCandidate.serializer(), candidateJson.replace("EXTRA", ""))
        assertNull(none.rhythmFit)
        val nul = LyricsJson.decodeFromString(RhymeCandidate.serializer(), candidateJson.replace("EXTRA", ""","rhythm_fit":null"""))
        assertNull(nul.rhythmFit)
    }

    private val responseJson = """{"anchors":[],"candidates":[],"total":0,"elapsed_ms":1.0EXTRA}"""

    private fun response(extra: String) = LyricsJson.decodeFromString(RhymeSuggestResponse.serializer(), responseJson.replace("EXTRA", extra))

    @Test
    fun `responses decode rhythm_applied and the unavailable reason`() {
        val applied = response(""","rhythm_applied":true,"rhythm_unavailable_reason":null""")
        assertTrue(applied.rhythmApplied)
        assertNull(applied.rhythmUnavailableReason)
        assertEquals(RhythmUnavailableReason.NO_SCORE, response(""","rhythm_applied":false,"rhythm_unavailable_reason":"no_score"""").rhythmUnavailableReason)
        assertEquals(RhythmUnavailableReason.SLOT_UNPLACED, response(""","rhythm_unavailable_reason":"slot_unplaced"""").rhythmUnavailableReason)
    }

    @Test
    fun `an old server's response has rhythm off and no reason`() {
        val old = response("")
        assertFalse(old.rhythmApplied)
        assertNull(old.rhythmUnavailableReason)
    }

    @Test
    fun `an unknown reason reads as OTHER`() {
        assertEquals(RhythmUnavailableReason.OTHER, response(""","rhythm_unavailable_reason":"anchor_is_text"""").rhythmUnavailableReason)
    }
}
