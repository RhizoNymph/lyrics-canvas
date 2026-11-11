package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.SearchLimits
import dev.nymph.lyrics.model.SearchResponse
import dev.nymph.lyrics.model.error.ApiResult

/** Search across the user's canvases and snippets (docs/features/global_search.md). */
interface SearchRepository {
    /**
     * `GET /api/search?q=&limit=`: [query] already trimmed and 1..200 code points; [limit] is
     * clamped to 1..[SearchLimits.MAX] (the server answers anything else with a generic 422).
     */
    suspend fun search(query: String, limit: Int = SearchLimits.DEFAULT): ApiResult<SearchResponse>
}

class HttpSearchRepository(private val api: ApiClient) : SearchRepository {
    override suspend fun search(query: String, limit: Int): ApiResult<SearchResponse> =
        api.sendQuery(HttpMethod.GET, listOf("api", "search"), listOf("q" to query, "limit" to limit.coerceIn(1, SearchLimits.MAX).toString()), SearchResponse.serializer())
}
