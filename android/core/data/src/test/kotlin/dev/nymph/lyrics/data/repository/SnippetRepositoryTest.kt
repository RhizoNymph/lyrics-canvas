package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnippetRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpSnippetRepository(api) }

    @Test
    fun `create posts to the canvas's snippets with derived_from`() = runTest {
        tokens.save("tok-1")
        enqueue(201, Bodies.SNIPPET)
        val body = SnippetCreateRequest(
            positionX = 340.0,
            positionY = 100.0,
            snippetType = SnippetType.FREESTYLE,
            rawText = "tin",
            derivedFrom = DerivedFrom(Provenance.DICTIONARY, "dict:tin", 0.9, 1, 2, "note:a:0:0", "hinge"),
        )
        val created = (repo.create(CanvasId("c1"), body) as ApiResult.Success).value
        assertEquals(SnippetId("s1"), created.id)
        val request = take()
        assertRequest(
            request,
            "POST",
            "/api/canvases/c1/snippets",
            """{"position_x":340.0,"position_y":100.0,"snippet_type":"freestyle","raw_text":"tin",
               "derived_from":{"provenance":"dictionary","source_unit_id":"dict:tin","score":0.9,"match_depth":1,"k":2,
               "anchor_unit_id":"note:a:0:0","anchor_text":"hinge"}}""",
        )
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
    }

    @Test
    fun `update puts raw_text and grid_data`() = runTest {
        enqueue(200, Bodies.SNIPPET)
        val grid = GridData(listOf(GridLine(listOf(GridCell("tin", 1, 0, word = "tin"), GridCell.REST), 1, "tin")))
        repo.update(SnippetId("s1"), SnippetUpdateRequest(rawText = "tin", gridData = grid))
        assertRequest(
            take(),
            "PUT",
            "/api/snippets/s1",
            """{"grid_data":{"lines":[{"cells":[{"syllable":"tin","stress":1,"word_index":0,"word":"tin"},{"rest":true}],
               "syllable_count":1,"raw_text":"tin"}]},"raw_text":"tin"}""",
        )
    }

    @Test
    fun `delete succeeds on 204 and maps 404`() = runTest {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Success(Unit), repo.delete(SnippetId("s1")))
        assertRequest(take(), "DELETE", "/api/snippets/s1")
        enqueue(404, """{"detail":"Snippet not found"}""")
        assertEquals(ApiResult.Failure(ApiError.NotFound("Snippet not found")), repo.delete(SnippetId("s9")))
    }

    @Test
    fun `batchPosition patches every update`() = runTest {
        enqueue(200, """{"updated":2}""")
        val result = repo.batchPosition(listOf(PositionUpdate(SnippetId("a"), 1.0, 2.0), PositionUpdate(SnippetId("b"), -3.5, 4.0)))
        assertEquals(ApiResult.Success(BatchPositionResponse(2)), result)
        assertRequest(
            take(),
            "PATCH",
            "/api/snippets/batch-position",
            """{"updates":[{"id":"a","position_x":1.0,"position_y":2.0},{"id":"b","position_x":-3.5,"position_y":4.0}]}""",
        )
    }

    @Test
    fun `merge posts ids and mode and returns the merged snippet and deleted ids`() = runTest {
        enqueue(200, """{"snippet":${Bodies.SNIPPET},"deleted_ids":["a","b"]}""")
        val request = MergeRequest.of(listOf(SnippetId("a"), SnippetId("b")), MergeMode.VERTICAL)!!
        val merged = (repo.merge(CanvasId("c1"), request) as ApiResult.Success).value
        assertEquals(SnippetId("s1"), merged.snippet.id)
        assertEquals(listOf(SnippetId("a"), SnippetId("b")), merged.deletedIds)
        assertRequest(take(), "POST", "/api/canvases/c1/merge", """{"snippet_ids":["a","b"],"mode":"vertical"}""")
    }

    @Test
    fun `merge maps a 422 to Validation`() = runTest {
        enqueue(422, """{"detail":"Need at least two distinct snippets"}""")
        val request = MergeRequest.of(listOf(SnippetId("a"), SnippetId("b")), MergeMode.HORIZONTAL)!!
        assertEquals(
            ApiResult.Failure(ApiError.Validation(422, "Need at least two distinct snippets")),
            repo.merge(CanvasId("c1"), request),
        )
    }
}
