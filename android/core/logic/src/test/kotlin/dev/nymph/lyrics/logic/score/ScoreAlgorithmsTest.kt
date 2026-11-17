package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.ScoreInvalidReason
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The spec's worked examples and the properties the vectors do not spell out. */
class ScoreAlgorithmsTest {
    @Test
    fun `section 4 example is notes_overlap at line 0 note 1`() {
        val raw = Json.parseToJsonElement(
            """{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":90}],"snap":"1/16","lines":[{"notes":[{"s":0,"t":0,"d":24,"a":0},{"s":1,"t":12,"d":12,"a":0}]}]}""",
        )
        val error = checkScore(raw, grid(line("I", "go"))).errorOrNull() as ScoreError.Invalid
        assertEquals(ScoreError.Invalid(ScoreInvalidReason.NOTES_OVERLAP, error.message, 0, 1), error)
        assertEquals("invalid_score", error.detail().error)
    }

    @Test
    fun `12_0 is an integer and true is not`() {
        val base = """{"v":1,"ppq":48.0,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":90}],"snap":"1/16","lines":[]}"""
        assertTrue(checkScore(Json.parseToJsonElement(base), null) is ScoreResult.Ok)
        val bad = base.replace("\"pickup\":0", "\"pickup\":true")
        assertEquals(ScoreInvalidReason.MALFORMED, (checkScore(Json.parseToJsonElement(bad), null).errorOrNull() as ScoreError.Invalid).reason)
    }

    @Test
    fun `typed validation agrees with the raw check`() {
        val s = score(listOf(note(0, 0, 24), note(1, 12, 12)))
        assertEquals(ScoreInvalidReason.NOTES_OVERLAP, validateScore(s, grid(line("I", "go")))?.reason)
        assertEquals(ScoreInvalidReason.LINE_COUNT_MISMATCH, validateScore(s, null)?.reason)
        assertNull(validateScore(score(listOf(note(0, 0, 12), note(1, 12, 12))), grid(line("I", "go"))))
        assertEquals(ScoreInvalidReason.TEMPO_INVALID, validateScore(score(tempos = listOf(ScoreTempo(0, 90.005))), null)?.reason)
    }

    @Test
    fun `section 5 example`() {
        val s = score(pickup = 24, meters = listOf(ScoreMeter(0, 4, 4), ScoreMeter(2, 3, 4), ScoreMeter(3, 6, 8)))
        assertEquals(listOf(0L, 24L, 216L, 408L, 552L, 696L), (-1..4).map { barStartTick(s, it) })
        assertEquals(BarPosition(-1, 10), barAtTick(s, 10))
        assertEquals(BarPosition(0, 0), barAtTick(s, 24))
        assertEquals(BarPosition(2, 92), barAtTick(s, 500))
        assertEquals(BarPosition(4, 4), barAtTick(s, 700))
        assertEquals(48, beatUnit(s, 10))
        assertEquals(24, beatUnit(s, 600))
        assertThrows<IllegalArgumentException> { barStartTick(s, -2) }
        assertThrows<IllegalArgumentException> { barAtTick(s, -1) }
    }

    @Test
    fun `section 6 examples`() {
        val four = score()
        assertEquals(listOf(3, 2, 1, 0, 3), listOf(0, 48, 24, 12, 192).map { metricWeight(four, it) })
        val six = score(meters = listOf(ScoreMeter(0, 6, 8)))
        assertEquals(listOf(3, 2, 1, 1, 0), listOf(0, 72, 24, 48, 12).map { metricWeight(six, it) })
        val three = score(meters = listOf(ScoreMeter(0, 3, 8)))
        assertEquals(listOf(2, 1), listOf(24, 12).map { metricWeight(three, it) })
        val pickup = score(pickup = 48)
        assertEquals(listOf(2, 1), listOf(0, 24).map { metricWeight(pickup, it) })
    }

    @Test
    fun `section 7 example and its inverse`() {
        val s = score(tempos = listOf(ScoreTempo(0, 120.0), ScoreTempo(1, 60.0)))
        assertEquals(1000.0, tickToMs(s, 96))
        assertEquals(3000.0, tickToMs(s, 240))
        assertEquals(96, msToTick(s, 1000.0))
        assertEquals(240, msToTick(s, 3000.0))
        assertEquals(0, msToTick(s, -5.0))
    }

    @Test
    fun `section 8 example and idempotence`() {
        val g = grid(line("I", "go", "now"))
        val s = score(listOf(note(0, 0, 24), note(1, 72, 24), note(2, 96, 24)))
        val linked = applyRestLink(s, g).getOrNull()!!
        assertEquals("I _ go now", linked.lines[0].rawText)
        assertEquals(linked, applyRestLink(s, linked).getOrNull())
        val short = score(listOf(note(0, 0, 24), note(1, 71, 24), note(2, 96, 24)))
        assertEquals(g, applyRestLink(short, g).getOrNull())
    }

    @Test
    fun `section 9 examples insert and clamp`() {
        val old = grid(line("I", "go", "now"))
        val inserted = grid(line("I", "go", "_", "_", "now"))
        val s = score(listOf(note(0, 0, 24), note(1, 24, 24), note(2, 48, 24)))
        val result = applyGridRestEdit(s, old, inserted).getOrNull()!!
        assertEquals(144, result.score.lines[0].notes[2].t)
        assertEquals(inserted.lines[0].cells, result.grid!!.lines[0].cells)

        val spaced = grid(line("I", "_", "_", "go", "now"))
        val spacedScore = score(listOf(note(0, 0, 24), note(1, 48, 24), note(2, 72, 24)))
        val clamped = applyGridRestEdit(spacedScore, spaced, old).getOrNull()!!
        assertEquals(listOf(0, 24, 48), clamped.score.lines[0].notes.map { it.t })
        assertEquals(old.lines[0].cells, clamped.grid!!.lines[0].cells)
    }

    @Test
    fun `a syllable change is not a rest-only edit`() {
        val s = score(listOf(note(0, 0, 24)))
        assertTrue(applyGridRestEdit(s, grid(line("I")), grid(line("you"))).errorOrNull() is ScoreError.NotRestOnly)
    }

    @Test
    fun `section 11 example`() {
        val old = grid(line("hello", "world"))
        val new = grid(line("hello", "big", "world"))
        val s = score(listOf(note(0, 0, 12, 1), note(1, 24, 12)))
        val out = reconcile(s, old, new).getOrNull()!!
        assertEquals(listOf(0 to 0, 2 to 24), out.lines[0].notes.map { it.s to it.t })
    }

    @Test
    fun `section 12 example round-trips through the rest link`() {
        val g = grid(line("I", "_", "go"), line("now"))
        val s = draftFromGrid(g, 4, 4, 90.0, ScoreSnap.EIGHTH).getOrNull()!!
        assertEquals(listOf(0 to 24, 72 to 24), s.lines[0].notes.map { it.t to it.d })
        assertEquals(192, s.lines[1].notes[0].t)
        assertEquals(g, applyRestLink(s, g).getOrNull())
        assertEquals(ScoreInvalidReason.METER_INVALID, (draftFromGrid(g, 4, 3, 90.0, ScoreSnap.EIGHTH).errorOrNull() as ScoreError.Invalid).reason)
        assertEquals(ScoreInvalidReason.TEMPO_INVALID, (draftFromGrid(g, 4, 4, 10.0, ScoreSnap.EIGHTH).errorOrNull() as ScoreError.Invalid).reason)
    }

    @Test
    fun `json keeps key order, integral bpm and extra note keys`() {
        val s = score(listOf(note(0, 0, 12).copy(extra = mapOf("p" to JsonPrimitive(60)))))
        val json = scoreToJson(s)
        assertEquals(listOf("v", "ppq", "pickup", "meters", "tempos", "snap", "lines"), json.keys.toList())
        assertEquals("90", json["tempos"]!!.jsonArray[0].jsonObject["bpm"].toString())
        val noteJson = json["lines"]!!.jsonArray[0].jsonObject["notes"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("s", "t", "d", "a", "p"), noteJson.keys.toList())
        assertEquals(s, checkScore(json, grid(line("x"))).getOrNull())
    }

    @Test
    fun `text splice skips blank lines`() {
        assertEquals("a\n\n b _\nc", spliceLineTexts("a\n\n b\nc", mapOf(1 to " b _")).getOrNull())
        assertEquals(ScoreError.TextMismatch(3, 3), spliceLineTexts("a\nb\nc", mapOf(3 to "x")).errorOrNull())
    }
}
