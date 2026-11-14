package dev.nymph.lyrics.logic.list

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every vector of `list_rank_vectors.json` (a byte-for-byte copy of the backend's canonical
 * `spec/list_rank_vectors.json`, in this module's test resources).
 */
class ListRankVectorsTest {
    private val root: JsonObject by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("list_rank_vectors.json")) { "list_rank_vectors.json missing" }
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }

    private fun section(name: String) = root.getValue(name).jsonArray

    private fun JsonObject.key(name: String): String? = get(name)?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    private fun rank(text: String?): ListRank? = text?.let { checkNotNull(ListRank.parse(it)) { "vector key should be valid: $it" } }

    private fun label(text: String?) = text?.let { "\"$it\"" } ?: "null"

    @TestFactory
    fun `every section is present and non-empty`(): List<DynamicTest> = listOf("between", "n_between", "invalid").map { name ->
        DynamicTest.dynamicTest(name) { assertTrue(section(name).isNotEmpty()) }
    }

    @TestFactory
    fun between(): List<DynamicTest> = section("between").map { it.jsonObject }.map { v ->
        val a = v.key("a")
        val b = v.key("b")
        val expected = v.getValue("key").jsonPrimitive.content
        DynamicTest.dynamicTest("${label(a)} .. ${label(b)} -> $expected") {
            assertEquals(RankResult.Ok(checkNotNull(ListRank.parse(expected))), keyBetween(rank(a), rank(b)))
        }
    }

    @TestFactory
    fun `n between`(): List<DynamicTest> = section("n_between").map { it.jsonObject }.map { v ->
        val a = v.key("a")
        val b = v.key("b")
        val n = v.getValue("n").jsonPrimitive.int
        val expected = v.getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        DynamicTest.dynamicTest("${label(a)} .. ${label(b)} x $n") {
            val keys = (keysBetween(rank(a), rank(b), n) as RankResult.Ok).value.map { it.value }
            assertEquals(expected, keys)
        }
    }

    @TestFactory
    fun invalid(): List<DynamicTest> = section("invalid").map { it.jsonPrimitive.content }.map { key ->
        DynamicTest.dynamicTest(label(key)) { assertNull(ListRank.parse(key)) }
    }

    @Test
    fun `every expected key is itself valid`() {
        section("between").map { it.jsonObject.getValue("key").jsonPrimitive.content }.forEach { assertTrue(ListRank.parse(it) != null, it) }
    }
}
