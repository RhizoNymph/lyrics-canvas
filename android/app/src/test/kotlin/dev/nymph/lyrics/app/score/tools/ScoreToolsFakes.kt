package dev.nymph.lyrics.app.score.tools

import dev.nymph.lyrics.data.DownloadedFile
import dev.nymph.lyrics.data.repository.ScoreToolsRepository
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreExportFormat
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlinx.coroutines.CompletableDeferred
import java.io.File

val DRAFT_SCORE = Score(
    meters = listOf(ScoreMeter(0, 4, 4)),
    tempos = listOf(ScoreTempo(0, 90.0)),
    snap = ScoreSnap.EIGHTH,
    lines = listOf(ScoreLine(listOf(ScoreNote(0, 0, 24, 1)))),
)

class FakeScoreToolsRepository : ScoreToolsRepository {
    val drafts = mutableListOf<Pair<SnippetId, ScoreDraftRequest>>()
    var draftResult: (ScoreDraftRequest) -> ApiResult<ScoreDraftResponse> = { ApiResult.Success(ScoreDraftResponse(DRAFT_SCORE)) }
    var draftGate: CompletableDeferred<Unit>? = null

    val exports = mutableListOf<Triple<SnippetId, ScoreExportFormat, File>>()
    var exportResult: (ScoreExportFormat, File) -> ApiResult<DownloadedFile> = { _, f ->
        f.parentFile?.mkdirs()
        f.writeText("score")
        ApiResult.Success(DownloadedFile(f, "Night drive.${f.extension}"))
    }
    var exportGate: CompletableDeferred<Unit>? = null

    override suspend fun draft(snippetId: SnippetId, request: ScoreDraftRequest): ApiResult<ScoreDraftResponse> {
        drafts += snippetId to request
        draftGate?.await()
        return draftResult(request)
    }

    override suspend fun export(snippetId: SnippetId, format: ScoreExportFormat, target: File): ApiResult<DownloadedFile> {
        exports += Triple(snippetId, format, target)
        exportGate?.await()
        return exportResult(format, target)
    }
}
