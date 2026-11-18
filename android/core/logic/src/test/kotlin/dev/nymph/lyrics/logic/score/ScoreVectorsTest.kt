package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreInvalidDetail
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.abs

/** Every case of spec/score_vectors.json, read in place (the `scoreVectors` system property). */
class ScoreVectorsTest {
    private val root: JsonObject by lazy {
        val path = checkNotNull(System.getProperty("scoreVectors")) { "scoreVectors system property missing" }
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    private fun cases(name: String, body: (JsonObject) -> Unit): List<DynamicTest> =
        section(name).mapIndexed { i, c -> DynamicTest.dynamicTest("$name #$i ${c["name"]?.jsonPrimitive?.content}") { body(c) } }

    private fun grid(e: JsonElement?): GridData? =
        if (e == null || e is JsonNull) null else LyricsJson.decodeFromJsonElement(GridData.serializer(), e)

    private fun score(e: JsonElement): Score = LyricsJson.decodeFromJsonElement(Score.serializer(), e)

    /** JSON equality with numbers compared numerically and objects ignoring key order. */
    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull &&
            a.content.toDoubleOrNull() != null && b.content.toDoubleOrNull() != null -> a.content.toDouble() == b.content.toDouble()
        else -> a == b
    }

    private fun assertScore(expected: JsonElement, actual: Score) {
        val written = scoreToJson(actual)
        assertTrue(jsonEquals(expected, written), "expected $expected\nactual   $written")
    }

    private fun expectInvalid(expect: JsonObject, result: ScoreResult<*>) {
        assertEquals("invalid_score", expect.getValue("error").jsonPrimitive.content)
        val error = result.errorOrNull() as? ScoreError.Invalid ?: fail("expected invalid_score, got $result")
        assertEquals(expect.getValue("reason").jsonPrimitive.content, error.detail().let { LyricsJson.encodeToJsonElement(ScoreInvalidDetail.serializer(), it).jsonObject.getValue("reason").jsonPrimitive.content })
    }

    @Test
    fun `vectors are version 1 with the spec constants`() {
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        val c = root.getValue("constants").jsonObject
        assertEquals(Score.PPQ, c.getValue("ppq").jsonPrimitive.int)
        assertEquals(Score.WHOLE, c.getValue("whole").jsonPrimitive.int)
        assertEquals(Score.MAX_TICKS, c.getValue("max_ticks").jsonPrimitive.int)
        val snaps = c.getValue("snap_ticks").jsonObject
        assertEquals(5, snaps.size)
        snaps.forEach { (wire, ticks) -> assertEquals(ticks.jsonPrimitive.int, ScoreRules.snapOf(wire)?.ticks) }
    }

    @TestFactory
    fun validate() = cases("validate") { c ->
        val g = grid(c["grid"])
        val expect = c.getValue("expect").jsonObject
        val result = checkScore(c.getValue("score"), g)
        if (expect.getValue("ok").jsonPrimitive.boolean) {
            val ok = result.getOrNull() ?: fail("expected ok, got $result")
            assertScore(c.getValue("score"), ok)
            assertEquals(null, validateScore(ok, g))
        } else {
            val error = result.errorOrNull() as? ScoreError.Invalid ?: fail("expected invalid, got $result")
            val detail = LyricsJson.encodeToJsonElement(ScoreInvalidDetail.serializer(), error.detail()).jsonObject
            assertEquals(expect.getValue("reason"), detail.getValue("reason"))
            assertEquals(expect["line"]?.jsonPrimitive?.int, error.line)
            assertEquals(expect["note"]?.jsonPrimitive?.int, error.note)
        }
    }

    @TestFactory
    fun bars() = cases("bars") { c ->
        val s = score(c.getValue("score"))
        for (b in c.getValue("bars").jsonArray.map { it.jsonObject }) {
            val bar = b.getValue("bar").jsonPrimitive.int
            assertEquals(b.getValue("start").jsonPrimitive.content.toLong(), barStartTick(s, bar), "start of bar $bar")
            assertEquals(b.getValue("length").jsonPrimitive.int, barLength(s, bar), "length of bar $bar")
        }
        assertEquals(c.getValue("end_tick").jsonPrimitive.int, endTick(s))
        assertEquals(c.getValue("bar_count").jsonPrimitive.int, barCount(s))
    }

    @TestFactory
    fun barAtTick() = cases("bar_at_tick") { c ->
        val s = score(c.getValue("score"))
        for (q in c.getValue("queries").jsonArray.map { it.jsonObject }) {
            val tick = q.getValue("tick").jsonPrimitive.int
            assertEquals(BarPosition(q.getValue("bar").jsonPrimitive.int, q.getValue("offset").jsonPrimitive.int), barAtTick(s, tick), "tick $tick")
        }
    }

    @TestFactory
    fun beatUnit() = cases("beat_unit") { c ->
        val s = score(c.getValue("score"))
        for (q in c.getValue("queries").jsonArray.map { it.jsonObject }) {
            val tick = q.getValue("tick").jsonPrimitive.int
            assertEquals(q.getValue("beat_unit").jsonPrimitive.int, beatUnit(s, tick), "tick $tick")
        }
    }

    @TestFactory
    fun metricWeight() = cases("metric_weight") { c ->
        val s = score(c.getValue("score"))
        for (q in c.getValue("queries").jsonArray.map { it.jsonObject }) {
            val tick = q.getValue("tick").jsonPrimitive.int
            assertEquals(q.getValue("weight").jsonPrimitive.int, metricWeight(s, tick), "tick $tick")
        }
    }

    @TestFactory
    fun tickToMs() = cases("tick_to_ms") { c ->
        val s = score(c.getValue("score"))
        for (q in c.getValue("queries").jsonArray.map { it.jsonObject }) {
            val tick = q.getValue("tick").jsonPrimitive.int
            val expected = q.getValue("ms").jsonPrimitive.double
            val actual = tickToMs(s, tick)
            assertTrue(abs(expected - actual) <= 1e-6, "tick $tick: expected $expected, got $actual")
        }
    }

    @TestFactory
    fun normalizeWord() = cases("normalize_word") { c ->
        val text = c.getValue("text").jsonPrimitive.content
        assertEquals(c.getValue("normalized").jsonPrimitive.content, normalizeWord(text))
        assertEquals(c.getValue("key").jsonPrimitive.content, wordKey(text))
    }

    @TestFactory
    fun lcs() = cases("lcs") { c ->
        val a = c.getValue("a").jsonArray.map { it.jsonPrimitive.content }
        val b = c.getValue("b").jsonArray.map { it.jsonPrimitive.content }
        val expected = c.getValue("pairs").jsonArray.map { p -> p.jsonArray[0].jsonPrimitive.int to p.jsonArray[1].jsonPrimitive.int }
        assertEquals(expected, lcsPairs(a, b))
    }

    @TestFactory
    fun lineKey() = cases("line_key") { c ->
        val line = LyricsJson.decodeFromJsonElement(GridLine.serializer(), c.getValue("line"))
        assertEquals(c.getValue("key").jsonPrimitive.content, lineKey(line))
    }

    @TestFactory
    fun textSplice() = cases("text_splice") { c ->
        val replacements = c.getValue("replacements").jsonObject.entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content }
        val result = spliceLineTexts(c.getValue("raw_text").jsonPrimitive.content, replacements)
        val expect = c.getValue("expect").jsonObject
        if (expect.containsKey("text")) {
            assertEquals(expect.getValue("text").jsonPrimitive.content, result.getOrNull())
        } else {
            assertEquals("text_mismatch", expect.getValue("error").jsonPrimitive.content)
            assertTrue(result.errorOrNull() is ScoreError.TextMismatch, "got $result")
        }
    }

    @TestFactory
    fun restLink() = cases("rest_link") { c ->
        val g = grid(c["grid"])
        val expect = c.getValue("expect").jsonObject
        val result = applyRestLink(score(c.getValue("score")), g)
        if (expect.containsKey("grid")) {
            assertTrue(result is ScoreResult.Ok, "expected a grid, got $result")
            assertEquals(grid(expect["grid"]), result.getOrNull())
            // Idempotent.
            assertEquals(result, applyRestLink(score(c.getValue("score")), result.getOrNull()))
        } else {
            expectInvalid(expect, result)
        }
    }

    @TestFactory
    fun restOnlyEdit() = cases("rest_only_edit") { c ->
        assertEquals(c.getValue("expect").jsonPrimitive.boolean, isRestOnlyEdit(grid(c["old_grid"]), grid(c["new_grid"])))
    }

    @TestFactory
    fun gridRestEdit() = cases("grid_rest_edit") { c ->
        val expect = c.getValue("expect").jsonObject
        val result = applyGridRestEdit(score(c.getValue("score")), grid(c["old_grid"]), grid(c["new_grid"]))
        when {
            expect.containsKey("score") -> {
                val ok = result.getOrNull() ?: fail("expected a result, got $result")
                assertScore(expect.getValue("score"), ok.score)
                assertEquals(grid(expect["grid"]), ok.grid)
            }
            expect["error"]?.jsonPrimitive?.content == "not_rest_only" -> assertTrue(result.errorOrNull() is ScoreError.NotRestOnly, "got $result")
            else -> expectInvalid(expect, result)
        }
    }

    @TestFactory
    fun reconcile() = cases("reconcile") { c ->
        val expect = c.getValue("expect").jsonObject
        val result = reconcile(score(c.getValue("score")), grid(c["old_grid"]), grid(c["new_grid"]))
        if (expect.containsKey("score")) {
            assertScore(expect.getValue("score"), result.getOrNull() ?: fail("expected a score, got $result"))
        } else {
            expectInvalid(expect, result)
        }
    }

    @TestFactory
    fun draftFromGrid() = cases("draft_from_grid") { c ->
        val g = grid(c["grid"])
        val expect = c.getValue("expect").jsonObject
        val result = draftFromGridRaw(g, c["meter"], c["bpm"], c["note"])
        if (expect.containsKey("score")) {
            val drafted = result.getOrNull() ?: fail("expected a score, got $result")
            assertScore(expect.getValue("score"), drafted)
            // A draft round-trips through the rest link unchanged.
            assertEquals(g, applyRestLink(drafted, g).getOrNull())
        } else {
            expectInvalid(expect, result)
        }
    }

    @Test
    fun `every vector score decodes to the typed wire model`() {
        val sections = listOf("bars", "bar_at_tick", "beat_unit", "metric_weight", "tick_to_ms", "rest_link", "grid_rest_edit", "reconcile")
        for (name in sections) for (c in section(name)) {
            try {
                score(c.getValue("score"))
            } catch (e: SerializationException) {
                fail("$name ${c["name"]}: ${e.message}")
            }
        }
    }
}
