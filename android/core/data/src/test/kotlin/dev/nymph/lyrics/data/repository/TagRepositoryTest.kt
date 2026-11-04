package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TagRepositoryTest : MockServerTest() {
    private val tags by lazy { HttpTagRepository(api) }
    private val presets by lazy { HttpTagPresetRepository(api) }

    private val tagBody = """{"id":"t1","name":"love","color":"#6366f1","snippet_count":2,"created_at":"2026-11-01"}"""
    private val presetBody = """{"id":"p1","name":"Dark","expression":"night","created_at":"a","updated_at":"b"}"""

    @Test
    fun `list gets the user's tags`() = runTest {
        enqueue(200, "[$tagBody]")
        assertEquals(listOf(UserTag(TagId("t1"), "love", "#6366f1", 2, "2026-11-01")), (tags.list() as ApiResult.Success).value)
        assertRequest(take(), "GET", "/api/tags")
    }

    @Test
    fun `create posts name and optional colour`() = runTest {
        enqueue(201, tagBody)
        tags.create("love")
        assertRequest(take(), "POST", "/api/tags", """{"name":"love"}""")
        enqueue(201, tagBody)
        tags.create("love", "#112233")
        assertRequest(take(), "POST", "/api/tags", """{"name":"love","color":"#112233"}""")
    }

    @Test
    fun `create maps a taken name to its code`() = runTest {
        enqueue(409, """{"detail":{"error":"tag_name_taken","message":"taken"}}""")
        assertEquals(ApiResult.Failure(ApiError.Server(409, "taken", "tag_name_taken")), tags.create("Love"))
    }

    @Test
    fun `update puts the given fields`() = runTest {
        enqueue(200, tagBody)
        tags.update(TagId("t1"), TagUpdateRequest(name = "romance"))
        assertRequest(take(), "PUT", "/api/tags/t1", """{"name":"romance"}""")
    }

    @Test
    fun `delete removes the tag`() = runTest {
        enqueue(204)
        assertEquals(ApiResult.Success(Unit), tags.delete(TagId("t1")))
        assertRequest(take(), "DELETE", "/api/tags/t1")
    }

    @Test
    fun `link posts the tag id as a query parameter`() = runTest {
        enqueue(200, """{"ok":true}""")
        assertEquals(ApiResult.Success(Unit), tags.link(SnippetId("s1"), TagId("t 1")))
        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/api/snippets/s1/tags?tag_id=t%201", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `unlink deletes the link`() = runTest {
        enqueue(204)
        assertEquals(ApiResult.Success(Unit), tags.unlink(SnippetId("s1"), TagId("t1")))
        assertRequest(take(), "DELETE", "/api/snippets/s1/tags/t1")
    }

    @Test
    fun `presets list, create, update and delete`() = runTest {
        enqueue(200, "[$presetBody]")
        assertEquals(listOf(TagPreset(TagPresetId("p1"), "Dark", "night", "a", "b")), (presets.list() as ApiResult.Success).value)
        assertRequest(take(), "GET", "/api/tag-presets")
        enqueue(201, presetBody)
        presets.create("Dark", "night")
        assertRequest(take(), "POST", "/api/tag-presets", """{"name":"Dark","expression":"night"}""")
        enqueue(200, presetBody)
        presets.update(TagPresetId("p1"), TagPresetUpdateRequest(name = "Darker"))
        assertRequest(take(), "PUT", "/api/tag-presets/p1", """{"name":"Darker"}""")
        enqueue(204)
        presets.delete(TagPresetId("p1"))
        assertRequest(take(), "DELETE", "/api/tag-presets/p1")
    }

    @Test
    fun `preset expression errors carry their location`() = runTest {
        enqueue(422, """{"detail":{"error":"invalid_tag_expression","message":"bad","expression_error":{"code":"unexpected_end","position":4,"message":"m"}}}""")
        assertEquals(
            ApiResult.Failure(ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("unexpected_end", 4, "m"))),
            presets.create("x", "a & "),
        )
    }
}
