package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.MockServerTest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.CanvasSearchHit
import dev.nymph.lyrics.model.SearchResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetSearchHit
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchRepositoryTest : MockServerTest() {
    private val repo by lazy { HttpSearchRepository(api) }

    @Test
    fun `search gets q and limit and decodes both lists`() = runTest {
        enqueue(
            200,
            """{"canvases":[{"id":"c1","title":"Night drafts","updated_at":"2026-11-10T10:00:00Z"}],
               "snippets":[{"snippet_id":"s1","snippet_type":"hook","content_updated_at":"2026-11-09T10:00:00Z",
                 "excerpt":"black cat at night","canvases":[{"id":"c1","title":"Night drafts"},{"id":"c2","title":"other"}]}]}""",
        )
        val result = repo.search("cat night", 20)
        val expected = SearchResponse(
            canvases = listOf(CanvasSearchHit(CanvasId("c1"), "Night drafts", "2026-11-10T10:00:00Z")),
            snippets = listOf(
                SnippetSearchHit(
                    SnippetId("s1"),
                    SnippetType.HOOK,
                    "2026-11-09T10:00:00Z",
                    "black cat at night",
                    listOf(CanvasRef(CanvasId("c1"), "Night drafts"), CanvasRef(CanvasId("c2"), "other")),
                ),
            ),
        )
        assertEquals(ApiResult.Success(expected), result)
        assertRequest(take(), "GET", "/api/search?q=cat%20night&limit=20")
    }

    @Test
    fun `the default limit is 50 and reserved characters are encoded`() = runTest {
        enqueue(200, """{"canvases":[],"snippets":[]}""")
        assertEquals(ApiResult.Success(SearchResponse()), repo.search("100% & _"))
        assertRequest(take(), "GET", "/api/search?q=100%25%20%26%20_&limit=50")
    }

    @Test
    fun `out-of-range limits are clamped before sending`() = runTest {
        enqueue(200, "{}")
        repo.search("x", 0)
        assertRequest(take(), "GET", "/api/search?q=x&limit=1")
        enqueue(200, "{}")
        repo.search("x", 999)
        assertRequest(take(), "GET", "/api/search?q=x&limit=200")
    }

    @Test
    fun `invalid_search_query keeps its code`() = runTest {
        enqueue(422, """{"detail":{"error":"invalid_search_query","message":"q must be 1-200 characters"}}""")
        assertEquals(
            ApiResult.Failure(ApiError.Validation(422, "q must be 1-200 characters", "invalid_search_query")),
            repo.search("x"),
        )
    }

    @Test
    fun `an unreadable body is a decoding failure`() = runTest {
        enqueue(200, """{"canvases":[{"id":"c1"}]}""")
        val result = repo.search("x")
        assertTrue((result as ApiResult.Failure).error is ApiError.Decoding)
    }
}
