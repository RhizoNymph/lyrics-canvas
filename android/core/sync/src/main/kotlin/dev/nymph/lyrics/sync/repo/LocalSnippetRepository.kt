package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.SnippetRepository
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
import dev.nymph.lyrics.sync.apply.LocalEdits

/** Snippets and placements, local-first: every change applies to the copy at once and is queued. */
class LocalSnippetRepository(private val local: LocalFirst, private val edits: LocalEdits) : SnippetRepository {
    override suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet> = local.edit { edits.createSnippet(it, canvasId, body) }

    override suspend fun update(canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> =
        local.edit { edits.updateSnippet(it, canvasId, id, body) }

    override suspend fun removeFromCanvas(canvasId: CanvasId, id: SnippetId): ApiResult<RemovePlacementResponse> =
        local.edit { edits.removeFromCanvas(it, canvasId, id) }

    override suspend fun deleteEverywhere(id: SnippetId): ApiResult<Unit> = local.edit { edits.deleteEverywhere(it, id) }

    override suspend fun batchPosition(canvasId: CanvasId, updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> =
        local.edit { edits.batchPosition(it, canvasId, updates) }

    override suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse> = local.edit { edits.merge(it, canvasId, request) }

    override suspend fun transfer(sourceCanvasId: CanvasId, request: TransferRequest): ApiResult<TransferResponse> =
        local.edit { edits.transfer(it, sourceCanvasId, request) }
}
