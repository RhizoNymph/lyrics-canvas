package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.BatchPositionRequest
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiResult

interface SnippetRepository {
    suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet>

    suspend fun update(id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet>

    suspend fun delete(id: SnippetId): ApiResult<Unit>

    /** Move several snippets at once (end of a drag). */
    suspend fun batchPosition(updates: List<PositionUpdate>): ApiResult<BatchPositionResponse>

    /** Server-side block merge: the merged snippet replaces the originals. */
    suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse>
}

class HttpSnippetRepository(private val api: ApiClient) : SnippetRepository {
    override suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet> = api.send(
        HttpMethod.POST,
        listOf("api", "canvases", canvasId.value, "snippets"),
        body,
        SnippetCreateRequest.serializer(),
        Snippet.serializer(),
    )

    override suspend fun update(id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> =
        api.send(HttpMethod.PUT, listOf("api", "snippets", id.value), body, SnippetUpdateRequest.serializer(), Snippet.serializer())

    override suspend fun delete(id: SnippetId): ApiResult<Unit> = api.delete(listOf("api", "snippets", id.value))

    override suspend fun batchPosition(updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> = api.send(
        HttpMethod.PATCH,
        listOf("api", "snippets", "batch-position"),
        BatchPositionRequest(updates),
        BatchPositionRequest.serializer(),
        BatchPositionResponse.serializer(),
    )

    override suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse> = api.send(
        HttpMethod.POST,
        listOf("api", "canvases", canvasId.value, "merge"),
        request,
        MergeRequest.serializer(),
        MergeResponse.serializer(),
    )
}
