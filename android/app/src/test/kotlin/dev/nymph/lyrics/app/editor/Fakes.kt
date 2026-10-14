package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

fun snippet(rawText: String, grid: GridData? = null, type: SnippetType = SnippetType.VERSE): Snippet = Snippet(
    id = SnippetId("s1"),
    canvasId = CanvasId("c1"),
    snippetType = type,
    gridMode = GridMode.AUTO,
    gridData = grid,
    rawText = rawText,
    positionX = 10.0,
    positionY = 20.0,
    width = 300.0,
    height = 120.0,
    zIndex = 0,
    createdAt = "2026-10-01T00:00:00Z",
    updatedAt = "2026-10-01T00:00:00Z",
    contentUpdatedAt = "2026-10-01T00:00:00Z",
)

/** A "server" syllabification: one stressed cell per word, so it is distinguishable from the local estimate. */
fun serverLine(text: String): GridLine {
    val words = text.trim().split(Regex("\\s+"))
    val cells = words.mapIndexed { i, w -> GridCell(syllable = w, stress = 1, wordIndex = i) }
    return GridLine(cells, cells.size, text.trim())
}

fun serverGrid(text: String): GridData = GridData(text.split("\n").filter { it.isNotBlank() }.map(::serverLine))

class FakeNlpRepository : NlpRepository {
    val calls = mutableListOf<String>()
    val completed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var latencyMs: Long = 0
    var respond: (String) -> ApiResult<GridData> = { ApiResult.Success(serverGrid(it)) }

    override suspend fun syllabify(text: String): ApiResult<GridData> {
        calls += text
        try {
            if (latencyMs > 0) delay(latencyMs)
        } catch (e: CancellationException) {
            cancelled += text
            throw e
        }
        completed += text
        return respond(text)
    }

    override suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse> =
        ApiResult.Failure(ApiError.Server(500, "not used"))
}

class FakeSnippetRepository : SnippetRepository {
    val updates = mutableListOf<Pair<SnippetId, SnippetUpdateRequest>>()
    var latencyMs: Long = 0
    var respond: (SnippetId, SnippetUpdateRequest) -> ApiResult<Snippet> = { _, body ->
        ApiResult.Success(
            snippet(body.rawText ?: "", body.gridData, body.snippetType ?: SnippetType.VERSE)
                .copy(updatedAt = "2026-10-01T00:00:01Z"),
        )
    }

    override suspend fun update(id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> {
        updates += id to body
        if (latencyMs > 0) delay(latencyMs)
        return respond(id, body)
    }

    private fun unused(): Nothing = error("not used by the editor")

    override suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet> = unused()

    override suspend fun delete(id: SnippetId): ApiResult<Unit> = unused()

    override suspend fun batchPosition(updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> = unused()

    override suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse> = unused()
}
