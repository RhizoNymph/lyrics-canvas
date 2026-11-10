package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.PlacementId
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SnippetPlacementRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpSnippetRepository(api) }

    @Test
    fun `update of a layout field names the canvas and decodes the placement context`() = runTest {
        enqueue(200, Bodies.SNIPPET)
        val s = (repo.update(CanvasId("c 2"), SnippetId("s1"), SnippetUpdateRequest(positionX = 5.0)) as ApiResult.Success).value
        assertEquals(PlacementId("p1"), s.placementId)
        assertEquals(listOf(CanvasRef(CanvasId("c1"), "Night drive")), s.canvases)
        val request = take()
        assertRequest(request, "PUT", "/api/snippets/s1?canvas_id=c%202", """{"position_x":5.0}""")
        assertEquals("c 2", request.requestUrl!!.queryParameter("canvas_id"))
    }

    @Test
    fun `update maps placement_not_found`() = runTest {
        enqueue(404, """{"detail":{"error":"placement_not_found","message":"Snippet is not on this canvas"}}""")
        val error = (repo.update(CanvasId("c9"), SnippetId("s1"), SnippetUpdateRequest(rawText = "x")) as ApiResult.Failure).error
        assertEquals(ApiError.NotFound("Snippet is not on this canvas", "placement_not_found"), error)
    }

    @Test
    fun `removeFromCanvas deletes the placement and reads snippet_deleted`() = runTest {
        enqueue(200, """{"snippet_deleted":false}""")
        assertEquals(ApiResult.Success(RemovePlacementResponse(false)), repo.removeFromCanvas(CanvasId("c1"), SnippetId("s1")))
        assertRequest(take(), "DELETE", "/api/canvases/c1/snippets/s1")
        enqueue(200, """{"snippet_deleted":true}""")
        assertEquals(ApiResult.Success(RemovePlacementResponse(true)), repo.removeFromCanvas(CanvasId("c1"), SnippetId("s2")))
        assertRequest(take(), "DELETE", "/api/canvases/c1/snippets/s2")
    }

    @Test
    fun `removeFromCanvas maps placement_not_found`() = runTest {
        enqueue(404, """{"detail":{"error":"placement_not_found","message":"gone"}}""")
        assertEquals(
            ApiResult.Failure(ApiError.NotFound("gone", "placement_not_found")),
            repo.removeFromCanvas(CanvasId("c1"), SnippetId("s1")),
        )
    }

    @Test
    fun `transfer posts to the source canvas and decodes the outcome`() = runTest {
        enqueue(200, """{"target_canvas_id":"c2","placed":["a"],"already_on_target":["b"],"removed_from_source":false}""")
        val request = TransferRequest.of(listOf(SnippetId("a"), SnippetId("b")), CanvasId("c2"), TransferMode.SHOW)!!
        val result = repo.transfer(CanvasId("c1"), request)
        assertEquals(ApiResult.Success(TransferResponse(CanvasId("c2"), listOf(SnippetId("a")), listOf(SnippetId("b")), false)), result)
        assertRequest(
            take(),
            "POST",
            "/api/canvases/c1/snippets/transfer",
            """{"snippet_ids":["a","b"],"target_canvas_id":"c2","mode":"show"}""",
        )
    }

    @Test
    fun `transfer maps same_canvas and canvas_not_found`() = runTest {
        val request = TransferRequest.of(listOf(SnippetId("a")), CanvasId("c1"), TransferMode.MOVE)!!
        enqueue(422, """{"detail":{"error":"same_canvas","message":"Target is the source"}}""")
        assertEquals(
            ApiResult.Failure(ApiError.Validation(422, "Target is the source", "same_canvas")),
            repo.transfer(CanvasId("c1"), request),
        )
        enqueue(404, """{"detail":{"error":"canvas_not_found","message":"Canvas not found"}}""")
        assertEquals(
            ApiResult.Failure(ApiError.NotFound("Canvas not found", "canvas_not_found")),
            repo.transfer(CanvasId("c1"), request),
        )
    }
}
