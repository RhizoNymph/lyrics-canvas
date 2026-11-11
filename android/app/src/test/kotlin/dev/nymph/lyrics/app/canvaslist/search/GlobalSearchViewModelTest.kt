package dev.nymph.lyrics.app.canvaslist.search

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.data.repository.SearchRepository
import dev.nymph.lyrics.logic.search.SearchError
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.CanvasSearchHit
import dev.nymph.lyrics.model.SearchResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetSearchHit
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException

private class FakeSearchRepository : SearchRepository {
    val queries = mutableListOf<String>()
    var respond: suspend (String) -> ApiResult<SearchResponse> = { ApiResult.Success(SearchResponse()) }

    override suspend fun search(query: String, limit: Int): ApiResult<SearchResponse> {
        queries += query
        return respond(query)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeSearchRepository()

    private val hits = SearchResponse(
        canvases = listOf(CanvasSearchHit(CanvasId("c1"), "Cat songs", "2026-11-01")),
        snippets = listOf(SnippetSearchHit(SnippetId("s1"), SnippetType.VERSE, "2026-11-02", "black cat", listOf(CanvasRef(CanvasId("c1"), "Cat songs")))),
    )

    private fun settle(scope: kotlinx.coroutines.test.TestScope) = scope.advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS + 1)

    @Test
    fun `a typed query is sent trimmed after the debounce, only the last of a burst`() = runTest(main.dispatcher) {
        repo.respond = { ApiResult.Success(hits) }
        val vm = GlobalSearchViewModel(repo)
        runCurrent()
        vm.edit("c")
        advanceTimeBy(100)
        vm.edit(" Cat ")
        assertEquals(" Cat ", vm.state.value.text)
        advanceTimeBy(GLOBAL_SEARCH_DEBOUNCE_MS - 10)
        assertEquals(emptyList<String>(), repo.queries)
        settle(this)
        runCurrent()
        assertEquals(listOf("Cat"), repo.queries)
        assertEquals(GlobalSearchResults.Loaded(SearchQuery.parse("Cat"), hits.canvases, hits.snippets), vm.state.value.results)
    }

    @Test
    fun `a blank field returns to the canvas list at once`() = runTest(main.dispatcher) {
        repo.respond = { ApiResult.Success(hits) }
        val vm = GlobalSearchViewModel(repo)
        vm.edit("cat")
        settle(this)
        runCurrent()
        vm.clear()
        runCurrent()
        assertEquals(GlobalSearchResults.Off, vm.state.value.results)
        assertEquals("", vm.state.value.text)
    }

    @Test
    fun `loading shows while the request is in flight and a newer query cancels it`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<ApiResult<SearchResponse>>()
        repo.respond = { q -> if (q == "slow") gate.await() else ApiResult.Success(SearchResponse()) }
        val vm = GlobalSearchViewModel(repo)
        vm.edit("slow")
        settle(this)
        runCurrent()
        assertEquals(GlobalSearchResults.Loading, vm.state.value.results)
        vm.edit("fast")
        settle(this)
        runCurrent()
        gate.complete(ApiResult.Success(hits))
        runCurrent()
        val loaded = vm.state.value.results as GlobalSearchResults.Loaded
        assertEquals(true, loaded.isEmpty)
        assertEquals(SearchQuery.parse("fast"), loaded.query)
    }

    @Test
    fun `an over-long query is refused without a request`() = runTest(main.dispatcher) {
        val vm = GlobalSearchViewModel(repo)
        vm.edit("x".repeat(201))
        settle(this)
        runCurrent()
        assertEquals(GlobalSearchResults.Failed(SearchError.InvalidQuery(null)), vm.state.value.results)
        assertEquals(emptyList<String>(), repo.queries)
    }

    @Test
    fun `failures are typed and retry sends the query again`() = runTest(main.dispatcher) {
        repo.respond = { ApiResult.Failure(ApiError.Network(IOException("down"))) }
        val vm = GlobalSearchViewModel(repo)
        vm.edit("cat")
        settle(this)
        runCurrent()
        assertEquals(GlobalSearchResults.Failed(SearchError.Network("down")), vm.state.value.results)
        repo.respond = { ApiResult.Failure(ApiError.Validation(422, "bad q", "invalid_search_query")) }
        vm.retry()
        settle(this)
        runCurrent()
        assertEquals(listOf("cat", "cat"), repo.queries)
        assertEquals(GlobalSearchResults.Failed(SearchError.InvalidQuery("bad q")), vm.state.value.results)
    }

    @Test
    fun `messages for every error`() {
        assertEquals(INVALID_SEARCH_MESSAGE, SearchError.InvalidQuery("x").userMessage())
        assertEquals("Can't reach the server. Check your connection.", SearchError.Network("x").userMessage())
        assertEquals("Search failed: boom", SearchError.Http(500, "boom").userMessage())
        assertEquals("Unexpected response from the server.", SearchError.Unexpected("x").userMessage())
        assertEquals(dev.nymph.lyrics.app.ui.SESSION_EXPIRED, SearchError.SessionExpired.userMessage())
        assertEquals("No matches", NO_MATCHES)
    }
}
