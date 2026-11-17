package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.DownloadedFile
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreExportFormat
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The server-only score routes (round-4 contract section 5). Both work on the server's copy of
 * the snippet. Failures carry the server's code in [dev.nymph.lyrics.model.error.ApiError.code];
 * map them with `toScoreToolError` (core/logic).
 */
interface ScoreToolsRepository {
    /** A draft score from the grid or a take; nothing is saved. */
    suspend fun draft(snippetId: SnippetId, request: ScoreDraftRequest): ApiResult<ScoreDraftResponse>

    /** The snippet's score as a [format] file in [target] (complete or absent afterwards). */
    suspend fun export(snippetId: SnippetId, format: ScoreExportFormat, target: File): ApiResult<DownloadedFile>
}

class HttpScoreToolsRepository(private val api: ApiClient) : ScoreToolsRepository {
    override suspend fun draft(snippetId: SnippetId, request: ScoreDraftRequest): ApiResult<ScoreDraftResponse> = api.send(
        HttpMethod.POST,
        scorePath(snippetId, "draft"),
        request.toJson(),
        JsonObject.serializer(),
        ScoreDraftResponse.serializer(),
    )

    override suspend fun export(snippetId: SnippetId, format: ScoreExportFormat, target: File): ApiResult<DownloadedFile> =
        api.downloadAttachment(scorePath(snippetId, "export"), listOf("format" to format.wire), target)

    private fun scorePath(id: SnippetId, action: String) = listOf("api", "snippets", id.value, "score", action)
}
