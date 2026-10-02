package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.Bodies
import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpCanvasRepository(api) }

    @Test
    fun `list gets the user's canvases`() = runTest {
        enqueue(200, "[${Bodies.CANVAS}]")
        val result = repo.list()
        assertEquals(listOf(CanvasId("c1")), (result as ApiResult.Success).value.map { it.id })
        assertRequest(take(), "GET", "/api/canvases")
    }

    @Test
    fun `create posts the title`() = runTest {
        enqueue(201, Bodies.CANVAS)
        assertEquals("Night drive", (repo.create("Night drive") as ApiResult.Success).value.title)
        assertRequest(take(), "POST", "/api/canvases", """{"title":"Night drive"}""")
    }

    @Test
    fun `get loads the canvas detail with its snippets`() = runTest {
        enqueue(200, Bodies.CANVAS_DETAIL)
        val detail = (repo.get(CanvasId("c1")) as ApiResult.Success).value
        assertEquals(listOf(SnippetId("s1")), detail.snippets.map { it.id })
        assertRequest(take(), "GET", "/api/canvases/c1")
    }

    @Test
    fun `saveViewport puts only the viewport state`() = runTest {
        enqueue(200, Bodies.CANVAS)
        val saved = repo.saveViewport(CanvasId("c1"), ViewportState(1.0, 2.0, 0.5))
        assertEquals(ViewportState(1.0, 2.0, 0.5), (saved as ApiResult.Success).value.viewportState)
        assertRequest(take(), "PUT", "/api/canvases/c1", """{"viewport_state":{"x":1.0,"y":2.0,"zoom":0.5}}""")
    }

    @Test
    fun `update sends the given fields and maps a missing canvas`() = runTest {
        enqueue(404, """{"detail":"Canvas not found"}""")
        assertEquals(ApiResult.Failure(ApiError.NotFound("Canvas not found")), repo.update(CanvasId("zz"), CanvasUpdateRequest(title = "t")))
        assertRequest(take(), "PUT", "/api/canvases/zz", """{"title":"t"}""")
    }
}
