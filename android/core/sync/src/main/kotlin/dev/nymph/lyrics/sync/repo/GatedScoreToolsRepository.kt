package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.DownloadedFile
import dev.nymph.lyrics.data.repository.ScoreToolsRepository
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.NOT_SYNCED_ERROR_CODE
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreExportFormat
import dev.nymph.lyrics.sync.outbox.EntityRef
import java.io.File

/** The answer when a draft or export would read a server copy that lacks this device's edits. */
val SCORE_NOT_SYNCED_YET: ApiError =
    ApiError.Validation(409, "Wait until this snippet's changes have synced, then try again", NOT_SYNCED_ERROR_CODE)

/**
 * Score drafts and exports need the server, and both read the server's copy of the snippet (its
 * grid, recordings and stored score). So the call waits until the snippet's queued changes have
 * synced ([LocalFirst.awaitSettled]); a parked conflict or a timeout answers [SCORE_NOT_SYNCED_YET].
 */
class GatedScoreToolsRepository(
    private val http: ScoreToolsRepository,
    private val gate: OnlineGate,
    private val local: LocalFirst,
) : ScoreToolsRepository {
    override suspend fun draft(snippetId: SnippetId, request: ScoreDraftRequest): ApiResult<ScoreDraftResponse> =
        settled(snippetId) { http.draft(snippetId, request) }

    override suspend fun export(snippetId: SnippetId, format: ScoreExportFormat, target: File): ApiResult<DownloadedFile> =
        settled(snippetId) { http.export(snippetId, format, target) }

    private suspend fun <T> settled(snippetId: SnippetId, block: suspend () -> ApiResult<T>): ApiResult<T> = gate.call {
        if (!local.awaitSettled(setOf(EntityRef.Snippet(snippetId)))) ApiResult.Failure(SCORE_NOT_SYNCED_YET) else block()
    }
}
