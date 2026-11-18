package dev.nymph.lyrics.model.score

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** The score wire model (spec/score_format.md section 2) and the shapes in spec/score_vectors.json. */
class ScoreDtoTest {
    private val example = """{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],
        "tempos":[{"bar":0,"bpm":90}],"snap":"1/16","lines":[{"notes":[{"s":0,"t":0,"d":12,"a":1}]}]}"""

    private fun decode(text: String): Score = LyricsJson.decodeFromString(Score.serializer(), text)

    private fun encode(score: Score): JsonObject = LyricsJson.encodeToJsonElement(Score.serializer(), score).jsonObject

    @Test
    fun `the spec example decodes`() {
        val score = decode(example)
        assertEquals(1, score.v)
        assertEquals(48, score.ppq)
        assertEquals(listOf(ScoreMeter(0, 4, 4)), score.meters)
        assertEquals(listOf(ScoreTempo(0, 90.0)), score.tempos)
        assertEquals(ScoreSnap.SIXTEENTH, score.snap)
        assertEquals(listOf(ScoreNote(0, 0, 12, 1)), score.lines.single().notes)
        assertEquals(192, score.meters.single().barLength)
    }

    @Test
    fun `extra note keys survive a round trip after the core keys`() {
        val text = example.replace(""""a":1}""", """"a":1,"p":60,"meta":{"x":[1,null]}}""")
        val note = decode(text).lines.single().notes.single()
        assertEquals(setOf("p", "meta"), note.extra.keys)
        val written = encode(decode(text))["lines"]!!.jsonArray[0].jsonObject["notes"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("s", "t", "d", "a", "p", "meta"), written.keys.toList())
        assertEquals(JsonPrimitive(60), written["p"])
    }

    @Test
    fun `v and ppq are always written`() {
        val out = encode(Score(meters = listOf(ScoreMeter(0, 6, 8)), tempos = listOf(ScoreTempo(0, 72.5)), snap = ScoreSnap.EIGHTH_TRIPLET, lines = emptyList()))
        assertEquals(JsonPrimitive(1), out["v"])
        assertEquals(JsonPrimitive(48), out["ppq"])
        assertEquals(JsonPrimitive("1/8t"), out["snap"])
    }

    @Test
    fun `integral floats are integers, other values are rejected`() {
        assertEquals(ScoreNote(0, 12, 6, 2), decode(example.replace(""""t":0,"d":12,"a":1""", """"t":12.0,"d":6.0,"a":2.0""")).lines[0].notes[0])
        for (bad in listOf("12.5", "true", "\"12\"", "null")) {
            assertThrows<SerializationException> { decode(example.replace(""""t":0""", """"t":$bad""")) }
        }
    }

    @Test
    fun `snap values and ticks`() {
        assertEquals(listOf(24, 12, 6, 16, 8), ScoreSnap.entries.map { it.ticks })
        assertEquals(""""1/16t"""", LyricsJson.encodeToString(ScoreSnap.serializer(), ScoreSnap.SIXTEENTH_TRIPLET))
        assertEquals(393216, Score.MAX_TICKS)
    }

    @Test
    fun `invalid_score detail decodes every reason`() {
        assertEquals(24, ScoreInvalidReason.entries.size)
        val detail = LyricsJson.decodeFromString(ScoreInvalidDetail.serializer(), """{"error":"invalid_score","reason":"notes_overlap","line":1,"note":0}""")
        assertEquals(ScoreInvalidDetail("invalid_score", ScoreInvalidReason.NOTES_OVERLAP, 1, 0), detail)
    }

    private val vectors: JsonObject by lazy {
        val path = checkNotNull(System.getProperty("scoreVectors")) { "scoreVectors system property missing" }
        LyricsJson.parseToJsonElement(File(path).readText()).jsonObject
    }

    private fun cases(section: String): List<JsonObject> = vectors[section]!!.jsonArray.map { it.jsonObject }

    private fun grids(case: JsonObject): List<JsonElement> =
        listOfNotNull(case["grid"], case["old_grid"], case["new_grid"], (case["expect"] as? JsonObject)?.get("grid")).filter { it !is JsonNull }

    @Test
    fun `every vector grid reads as GridData`() {
        var count = 0
        for (section in listOf("validate", "bars", "rest_link", "rest_only_edit", "grid_rest_edit", "reconcile", "draft_from_grid")) {
            for (case in cases(section)) {
                for (grid in grids(case)) {
                    LyricsJson.decodeFromJsonElement(GridData.serializer(), grid)
                    count++
                }
            }
        }
        assertTrue(count > 500, "decoded $count grids")
    }

    @Test
    fun `every canonical vector score reads and writes back as the same JSON`() {
        // Valid validate inputs, except the one written with integral floats (12.0): validate reads raw
        // JSON, while the typed model reads what the server writes (canonical integers), as do the outputs.
        val scores = mutableListOf<JsonElement>()
        cases("validate")
            .filter { it["expect"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean && it["name"]!!.jsonPrimitive.content != "ok integral floats" }
            .forEach { scores += it["score"]!! }
        for (section in listOf("grid_rest_edit", "reconcile", "draft_from_grid")) {
            cases(section).mapNotNullTo(scores) { (it["expect"] as JsonObject)["score"] }
        }
        for (raw in scores) {
            val score = LyricsJson.decodeFromJsonElement(Score.serializer(), raw)
            assertEquals(numbersAsDoubles(raw), numbersAsDoubles(LyricsJson.encodeToJsonElement(Score.serializer(), score)))
        }
        assertTrue(scores.size > 100)
    }

    /** JSON value equality with numbers compared numerically (90 == 90.0). */
    private fun numbersAsDoubles(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { numbersAsDoubles(it.value) }
        is JsonArray -> element.map { numbersAsDoubles(it) }
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) element.content else element.content.toDoubleOrNull() ?: element.content
    }
}
