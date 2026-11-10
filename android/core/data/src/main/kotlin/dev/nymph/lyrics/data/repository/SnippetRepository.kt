package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.BatchPositionRequest
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.error.ApiResult

/**
 * Snippets and their placements. Every call that touches layout names the canvas whose placement it
 * changes: there is no canvas-less update.
 */
interface SnippetRepository {
    /** Creates the snippet and its placement on [canvasId]. */
    suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet>

    /**
     * `PUT /api/snippets/{id}?canvas_id={canvasId}`: content fields change the shared snippet, layout
     * fields its placement on [canvasId]; the answer is the snippet in that canvas's context.
     */
    suspend fun update(canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet>

    /** `DELETE /api/canvases/{cid}/snippets/{sid}`: remove from this canvas (deleted for good when it was the last one). */
    suspend fun removeFromCanvas(canvasId: CanvasId, id: SnippetId): ApiResult<RemovePlacementResponse>

    /** `DELETE /api/snippets/{id}`: delete everywhere, with every placement and recording. */
    suspend fun deleteEverywhere(id: SnippetId): ApiResult<Unit>

    /** Move several snippets on [canvasId] at once (end of a drag). */
    suspend fun batchPosition(canvasId: CanvasId, updates: List<PositionUpdate>): ApiResult<BatchPositionResponse>

    /** Server-side block merge: the merged snippet replaces the originals on [canvasId]. */
    suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse>

    /** Move or also show snippets of [sourceCanvasId] on another canvas. */
    suspend fun transfer(sourceCanvasId: CanvasId, request: TransferRequest): ApiResult<TransferResponse>
}

class HttpSnippetRepository(private val api: ApiClient) : SnippetRepository {
    override suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet> = api.send(
        HttpMethod.POST,
        listOf("api", "canvases", canvasId.value, "snippets"),
        body,
        SnippetCreateRequest.serializer(),
        Snippet.serializer(),
    )

    override suspend fun update(canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> = api.send(
        HttpMethod.PUT,
        listOf("api", "snippets", id.value),
        body,
        SnippetUpdateRequest.serializer(),
        Snippet.serializer(),
        query = listOf("canvas_id" to canvasId.value),
    )

    override suspend fun removeFromCanvas(canvasId: CanvasId, id: SnippetId): ApiResult<RemovePlacementResponse> = api.sendQuery(
        HttpMethod.DELETE,
        listOf("api", "canvases", canvasId.value, "snippets", id.value),
        emptyList(),
        RemovePlacementResponse.serializer(),
    )

    override suspend fun deleteEverywhere(id: SnippetId): ApiResult<Unit> = api.delete(listOf("api", "snippets", id.value))

    override suspend fun batchPosition(canvasId: CanvasId, updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> = api.send(
        HttpMethod.PATCH,
        listOf("api", "snippets", "batch-position"),
        BatchPositionRequest(canvasId, updates),
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

    override suspend fun transfer(sourceCanvasId: CanvasId, request: TransferRequest): ApiResult<TransferResponse> = api.send(
        HttpMethod.POST,
        listOf("api", "canvases", sourceCanvasId.value, "snippets", "transfer"),
        request,
        TransferRequest.serializer(),
        TransferResponse.serializer(),
    )
}
