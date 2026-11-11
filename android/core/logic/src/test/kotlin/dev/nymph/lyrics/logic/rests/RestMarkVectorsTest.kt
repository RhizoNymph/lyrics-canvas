package dev.nymph.lyrics.logic.rests

import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.logic.grid.rebuildRawText
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every vector of `rest_mark_vectors.json` (a byte-for-byte copy of the backend's canonical
 * `backend/tests/fixtures/rest_mark_vectors.json`, in this module's test resources).
 */
class RestMarkVectorsTest {
    private val root: JsonObject by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("rest_mark_vectors.json")) { "rest_mark_vectors.json missing" }
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }

    private fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    private fun label(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    /** A vector token as the test compares it: the word, or null for a rest. */
    private fun expectedToken(token: JsonObject): String? =
        if (token["rest"]?.jsonPrimitive?.boolean == true) null else token.getValue("word").jsonPrimitive.content

    private fun actualToken(token: LineToken): String? = when (token) {
        LineToken.Rest -> null
        is LineToken.Word -> token.text
    }

    @Test
    fun `vectors are version 1`() {
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
    }

    @TestFactory
    fun `every section is present and non-empty`(): List<DynamicTest> =
        listOf("tokenize", "word_indexes", "rebuild", "display", "match").map { name ->
            DynamicTest.dynamicTest(name) { assertTrue(section(name).isNotEmpty()) }
        }

    @TestFactory
    fun tokenize(): List<DynamicTest> = section("tokenize").map { v ->
        val line = v.getValue("line").jsonPrimitive.content
        DynamicTest.dynamicTest(label(line)) {
            val expected = v.getValue("tokens").jsonArray.map { expectedToken(it.jsonObject) }
            assertEquals(expected, tokenizeLine(line).map(::actualToken))
        }
    }

    @TestFactory
    fun `local syllabify emits a rest cell for every rest token`(): List<DynamicTest> = section("tokenize").map { v ->
        val line = v.getValue("line").jsonPrimitive.content
        DynamicTest.dynamicTest(label(line)) {
            val expected = v.getValue("tokens").jsonArray.map { expectedToken(it.jsonObject) }
            val cells = localSyllabifyLine(line).cells
            // Collapse each word's syllables back to one entry: the cell sequence then reads as the tokens.
            val collapsed = mutableListOf<String?>()
            var lastWord: Int? = null
            for (cell in cells) {
                if (cell.isRest) {
                    assertEquals(GridCell.REST, cell)
                    collapsed += null
                    lastWord = null
                } else if (cell.wordIndex != lastWord) {
                    collapsed += cell.word
                    lastWord = cell.wordIndex
                }
            }
            assertEquals(expected, collapsed)
            assertEquals(cells.count { !it.isRest }, localSyllabifyLine(line).syllableCount)
        }
    }

    @TestFactory
    fun `word indexes`(): List<DynamicTest> = section("word_indexes").map { v ->
        val line = v.getValue("line").jsonPrimitive.content
        DynamicTest.dynamicTest(label(line)) {
            val expected = v.getValue("word_index_per_token").jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.int }
            val actual = tokenizeLine(line).map { (it as? LineToken.Word)?.wordIndex }
            assertEquals(expected, actual)
            val cells = localSyllabifyLine(line).cells
            assertEquals(expected.filterNotNull().distinct(), cells.mapNotNull { it.wordIndex }.distinct())
        }
    }

    @TestFactory
    fun rebuild(): List<DynamicTest> = section("rebuild").map { v ->
        val text = v.getValue("text").jsonPrimitive.content
        DynamicTest.dynamicTest(label(text)) {
            val cells = LyricsJson.decodeFromJsonElement(ListSerializer(GridCell.serializer()), v.getValue("cells"))
            assertEquals(text, rebuildRawText(GridLine(cells, cells.count { !it.isRest }, "stale")))
        }
    }

    @TestFactory
    fun display(): List<DynamicTest> = section("display").map { v ->
        val text = v.getValue("text").jsonPrimitive.content
        DynamicTest.dynamicTest(label(text)) {
            assertEquals(v.getValue("display").jsonPrimitive.content, displayText(text))
        }
    }

    @TestFactory
    fun match(): List<DynamicTest> = section("match").map { v ->
        val query = v.getValue("query").jsonPrimitive.content
        val text = v.getValue("text").jsonPrimitive.content
        DynamicTest.dynamicTest("${label(query)} in ${label(text)}") {
            assertEquals(v.getValue("matches").jsonPrimitive.boolean, SearchQuery.parse(query).matches(text))
        }
    }
}
