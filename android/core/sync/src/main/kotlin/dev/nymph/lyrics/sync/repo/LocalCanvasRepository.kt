package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.PlacementErrorCode
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.sync.apply.Edited
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.store.Views
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Canvases from the local copy; changes are saved locally and queued (docs/features/android_offline_sync.md). */
class LocalCanvasRepository(private val local: LocalFirst, private val edits: LocalEdits) : CanvasRepository {
    override suspend fun list(): ApiResult<List<Canvas>> = local.read { ApiResult.Success(Views.canvasList(it)) }

    override suspend fun create(title: String): ApiResult<Canvas> = local.edit { edits.createCanvas(it, title) }

    override suspend fun get(id: CanvasId): ApiResult<CanvasDetail> = local.read { d ->
        Views.canvasDetail(d, id)?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError.NotFound("Canvas not found", PlacementErrorCode.CANVAS_NOT_FOUND.wire))
    }

    /** Title and viewport (the only fields the app changes); `is_public` is not offline-capable and is ignored. */
    override suspend fun update(id: CanvasId, update: CanvasUpdateRequest): ApiResult<Canvas> = local.edit { d ->
        var edited: Edited<Canvas> = d.canvas(id)?.let { Edited(d, ApiResult.Success(it)) }
            ?: Edited(d, ApiResult.Failure(ApiError.NotFound("Canvas not found", PlacementErrorCode.CANVAS_NOT_FOUND.wire)))
        update.title?.let { title -> edited = edits.renameCanvas(edited.data, id, title) }
        update.viewportState?.let { vp -> if (edited.result is ApiResult.Success) edited = edits.saveViewport(edited.data, id, vp) }
        edited
    }

    override suspend fun delete(id: CanvasId): ApiResult<Unit> = local.edit { edits.deleteCanvas(it, id) }

    override fun observeList(): Flow<List<Canvas>> = local.store.data.map(Views::canvasList).distinctUntilChanged()

    override fun observe(id: CanvasId): Flow<CanvasDetail?> = local.store.data.map { Views.canvasDetail(it, id) }.distinctUntilChanged()
}
