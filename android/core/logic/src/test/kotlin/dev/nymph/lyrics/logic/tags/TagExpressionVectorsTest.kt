package dev.nymph.lyrics.logic.tags

import dev.nymph.lyrics.model.SnippetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Every vector of `spec/tag_expression_vectors.json` (shared with the backend and the web client),
 * read from the repo root through the `tagExprVectors` system property set by the Gradle test task.
 */
class TagExpressionVectorsTest {
    private val root: JsonObject by lazy {
        val path = checkNotNull(System.getProperty("tagExprVectors")) { "tagExprVectors system property not set" }
        Json.parseToJsonElement(File(path).readText(Charsets.UTF_8)).jsonObject
    }

    private fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    /** A string, or segments (strings and `{"repeat": s, "times": n}`) concatenated. */
    private fun expand(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("") { segment ->
            if (segment is JsonPrimitive) segment.content else segment.jsonObject.let { it.getValue("repeat").jsonPrimitive.content.repeat(it.getValue("times").jsonPrimitive.int) }
        }
        is JsonObject -> error("unexpected object $value")
    }

    /** A display name: JSON-quoted (blank inputs are vectors too), long inputs shortened. */
    private fun label(text: String): String = JsonPrimitive(if (text.length > 40) text.take(40) + "…(${text.length})" else text).toString()

    @Test
    fun `vectors are version 2`() {
        assertEquals(2, root.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun `typeless eval subjects are covered`() {
        assertTrue(section("eval").count { it.getValue("snippet").jsonObject.getValue("type") is JsonNull } >= 8)
    }

    @TestFactory
    fun `every section is present and non-empty`(): List<DynamicTest> = listOf("parse_ok", "parse_error", "eval", "rename").map { name ->
        DynamicTest.dynamicTest(name) { assertTrue(section(name).isNotEmpty()) }
    }

    @TestFactory
    fun `parse_ok vectors normalize and are idempotent`(): List<DynamicTest> = section("parse_ok").map { v ->
        val input = expand(v.getValue("input"))
        val normalized = expand(v.getValue("normalized"))
        DynamicTest.dynamicTest(label(input)) {
            val parsed = parseTagExpression(input)
            assertTrue(parsed is TagExprParse.Valid, "expected valid, got $parsed")
            val formatted = (parsed as TagExprParse.Valid).filter.format()
            assertEquals(normalized, formatted)
            assertEquals(formatted, (parseTagExpression(formatted) as TagExprParse.Valid).filter.format())
        }
    }

    @TestFactory
    fun `parse_error vectors fail with code and code point position`(): List<DynamicTest> = section("parse_error").map { v ->
        val input = expand(v.getValue("input"))
        val code = v.getValue("code").jsonPrimitive.content
        val position = v.getValue("position").jsonPrimitive.int
        DynamicTest.dynamicTest("${label(input)} -> $code@$position") {
            val parsed = parseTagExpression(input)
            assertTrue(parsed is TagExprParse.Invalid, "expected an error, got $parsed")
            val error = (parsed as TagExprParse.Invalid).error
            assertEquals(code to position, error.code.wire to error.position)
            assertTrue(error.message.isNotBlank())
        }
    }

    @TestFactory
    fun `eval vectors`(): List<DynamicTest> = section("eval").map { v ->
        val expression = v.getValue("expression").jsonPrimitive.content
        val snippet = v.getValue("snippet").jsonObject
        // A null type is a typeless subject (a word bank word; vectors v2).
        val type = snippet.getValue("type").jsonPrimitive.contentOrNull
        val tags = snippet.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
        val matches = v.getValue("matches").jsonPrimitive.boolean
        DynamicTest.dynamicTest("$expression on $type $tags") {
            val filter = (parseTagExpression(expression) as TagExprParse.Valid).filter
            val snippetType = type?.let { wire -> SnippetType.entries.single { it.wire == wire } }
            assertEquals(matches, filter.matches(snippetType, tags))
        }
    }

    @TestFactory
    fun `rename vectors`(): List<DynamicTest> = section("rename").map { v ->
        val expression = v.getValue("expression").jsonPrimitive.content
        val from = v.getValue("from").jsonPrimitive.content
        val to = v.getValue("to").jsonPrimitive.content
        DynamicTest.dynamicTest("$expression: $from -> $to") {
            val filter = (parseTagExpression(expression) as TagExprParse.Valid).filter
            assertEquals(v.getValue("result").jsonPrimitive.content, filter.renameTag(from, to).format())
        }
    }
}
