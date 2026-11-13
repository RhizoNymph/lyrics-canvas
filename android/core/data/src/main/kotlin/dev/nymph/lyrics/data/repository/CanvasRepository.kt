package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasCreateRequest
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.ViewportState
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.builtins.ListSerializer

interface CanvasRepository {
    suspend fun list(): ApiResult<List<Canvas>>

    suspend fun create(title: String): ApiResult<Canvas>

    suspend fun get(id: CanvasId): ApiResult<CanvasDetail>

    suspend fun update(id: CanvasId, update: CanvasUpdateRequest): ApiResult<Canvas>

    /** Persist the camera (the web saves it the same way, through the canvas update route). */
    suspend fun saveViewport(id: CanvasId, viewport: ViewportState): ApiResult<Canvas> =
        update(id, CanvasUpdateRequest(viewportState = viewport))

    /** Rename (`PUT /api/canvases/{id}` with only `title`); a bad title is 422 `invalid_canvas_title`. */
    suspend fun rename(id: CanvasId, title: String): ApiResult<Canvas> = update(id, CanvasUpdateRequest(title = title))

    /** `DELETE /api/canvases/{id}`: the canvas and the snippets only on it. Unsupported unless overridden. */
    suspend fun delete(id: CanvasId): ApiResult<Unit> = ApiResult.Failure(ApiError.Validation(405, "Deleting canvases is not supported here"))

    /** The list as it changes (local copy: every edit and sync); a server-only repository emits nothing. */
    fun observeList(): Flow<List<Canvas>> = emptyFlow()

    /** One canvas as it changes; null once it is gone. */
    fun observe(id: CanvasId): Flow<CanvasDetail?> = emptyFlow()
}

class HttpCanvasRepository(private val api: ApiClient) : CanvasRepository {
    override suspend fun list(): ApiResult<List<Canvas>> = api.get(listOf("api", "canvases"), ListSerializer(Canvas.serializer()))

    override suspend fun create(title: String): ApiResult<Canvas> =
        api.send(HttpMethod.POST, listOf("api", "canvases"), CanvasCreateRequest(title), CanvasCreateRequest.serializer(), Canvas.serializer())

    override suspend fun get(id: CanvasId): ApiResult<CanvasDetail> = api.get(listOf("api", "canvases", id.value), CanvasDetail.serializer())

    override suspend fun update(id: CanvasId, update: CanvasUpdateRequest): ApiResult<Canvas> =
        api.send(HttpMethod.PUT, listOf("api", "canvases", id.value), update, CanvasUpdateRequest.serializer(), Canvas.serializer())

    override suspend fun delete(id: CanvasId): ApiResult<Unit> = api.delete(listOf("api", "canvases", id.value))
}
