package dev.nymph.lyrics.sync.engine

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasCreateRequest
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.SyncErrorCode
import dev.nymph.lyrics.model.SyncSnapshot
import dev.nymph.lyrics.model.TagCreateRequest
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagLinkRequest
import dev.nymph.lyrics.model.TagLinkResponse
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.NewBankWord
import dev.nymph.lyrics.model.WordBankSyncAddRequest
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.WordBankDeleteRequest
import dev.nymph.lyrics.model.WordBankRenameRequest
import dev.nymph.lyrics.model.WordBankTagRequest
import dev.nymph.lyrics.model.WordBankTagResponse
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.outbox.Batch
import dev.nymph.lyrics.sync.outbox.SyncOp
import kotlinx.serialization.builtins.ListSerializer
import java.util.Locale

/** The sync engine's view of the server. */
interface OpSender {
    suspend fun send(batch: Batch): SendOutcome

    suspend fun snapshot(): ApiResult<SyncSnapshot>

    /** Who the token belongs to (the store's account, when it does not know yet). */
    suspend fun me(): ApiResult<User>
}

/** Statuses that say "try again later" rather than "no". */
private val RETRYABLE_4XX = setOf(408, 429)

/**
 * Classify a call's result. [isDelete]: a 404 means the thing is already gone, which is what the
 * operation wanted.
 */
fun <T> classify(result: ApiResult<T>, isDelete: Boolean = false, ok: (T) -> OpResult): SendOutcome = when (result) {
    is ApiResult.Success -> SendOutcome.Ok(ok(result.value))
    is ApiResult.Failure -> when (val e = result.error) {
        is ApiError.Network, ApiError.NeedsConnection, is ApiError.Unauthorized -> SendOutcome.Transient(e)
        is ApiError.Decoding -> SendOutcome.Unreadable(e)
        is ApiError.NotFound -> if (isDelete) SendOutcome.Ok(OpResult.Done) else SendOutcome.NotFound(e.code)
        is ApiError.Validation -> SendOutcome.Refused(e)
        is ApiError.Server -> when {
            e.status == 409 && SyncErrorCode.parse(e.code) == SyncErrorCode.VERSION_CONFLICT -> SendOutcome.VersionConflict(e.current)
            e.status >= 500 || e.status in RETRYABLE_4XX -> SendOutcome.Transient(e)
            else -> SendOutcome.Refused(e)
        }
    }
}

/** The operations as API calls (docs/features/sync_versions.md). */
class HttpOpSender(private val api: ApiClient) : OpSender {
    override suspend fun snapshot(): ApiResult<SyncSnapshot> = api.get(listOf("api", "sync", "snapshot"), SyncSnapshot.serializer())

    override suspend fun me(): ApiResult<User> = api.get(listOf("api", "auth", "me"), User.serializer())

    private fun base(version: Int) = listOf("base_version" to version.toString())

    override suspend fun send(batch: Batch): SendOutcome = when (val op = batch.first.op) {
        is SyncOp.CreateCanvas -> classify(
            api.send(HttpMethod.POST, listOf("api", "canvases"), CanvasCreateRequest(op.title, op.id), CanvasCreateRequest.serializer(), Canvas.serializer()),
        ) { OpResult.CanvasSaved(it) }
        is SyncOp.RenameCanvas -> putCanvas(op.id.value, CanvasUpdateRequest(title = op.title, baseVersion = op.baseVersion))
        is SyncOp.SaveViewport -> putCanvas(op.id.value, CanvasUpdateRequest(viewportState = op.viewport))
        is SyncOp.DeleteCanvas -> classify(api.delete(listOf("api", "canvases", op.id.value), base(op.baseVersion)), isDelete = true) { OpResult.Done }

        is SyncOp.CreateSnippet -> classify(
            api.send(
                HttpMethod.POST,
                listOf("api", "canvases", op.canvasId.value, "snippets"),
                op.body.copy(id = op.id),
                SnippetCreateRequest.serializer(),
                Snippet.serializer(),
            ),
        ) { OpResult.SnippetSaved(it) }
        is SyncOp.EditSnippet -> putSnippet(
            SnippetUpdateRequest(
                snippetType = op.patch.snippetType,
                gridMode = op.patch.gridMode,
                beatsPerLine = op.patch.beatsPerLine,
                gridData = op.patch.gridData,
                rawText = op.patch.rawText,
                baseVersion = op.baseVersion,
            ),
            op.canvasId.value,
            op.id.value,
        )
        is SyncOp.MoveSnippet -> putSnippet(
            SnippetUpdateRequest(
                positionX = op.layout.positionX,
                positionY = op.layout.positionY,
                width = op.layout.width,
                height = op.layout.height,
                zIndex = op.layout.zIndex,
                groupId = op.layout.groupId,
            ),
            op.canvasId.value,
            op.id.value,
        )
        is SyncOp.RemoveFromCanvas -> classify(
            api.sendQuery(HttpMethod.DELETE, listOf("api", "canvases", op.canvasId.value, "snippets", op.id.value), base(op.baseVersion), RemovePlacementResponse.serializer()),
            isDelete = true,
        ) { OpResult.Done }
        is SyncOp.DeleteSnippet -> classify(api.delete(listOf("api", "snippets", op.id.value), base(op.baseVersion)), isDelete = true) { OpResult.Done }
        is SyncOp.SnippetTag -> if (op.link) {
            classify(
                api.send(
                    HttpMethod.POST,
                    listOf("api", "snippets", op.snippetId.value, "tags"),
                    TagLinkRequest(op.baseVersion),
                    TagLinkRequest.serializer(),
                    TagLinkResponse.serializer(),
                    query = listOf("tag_id" to op.tagId.value),
                ),
            ) { OpResult.SnippetVersioned(op.snippetId, it.version) }
        } else {
            classify(
                api.sendQuery(HttpMethod.DELETE, listOf("api", "snippets", op.snippetId.value, "tags", op.tagId.value), base(op.baseVersion), TagLinkResponse.serializer()),
            ) { OpResult.SnippetVersioned(op.snippetId, it.version) }
        }
        is SyncOp.Merge -> {
            val request = checkNotNull(MergeRequest.of(op.sources.map { it.id }, op.mode)) { "a parked merge has two sources" }
                .forSync(op.mergedId, op.sources.associate { it.id to it.version })
            classify(
                api.send(HttpMethod.POST, listOf("api", "canvases", op.canvasId.value, "merge"), request, MergeRequest.serializer(), MergeResponse.serializer()),
            ) { OpResult.Merged(it) }
        }
        is SyncOp.Transfer -> {
            val request = checkNotNull(TransferRequest.of(op.snippetIds, op.targetCanvasId, op.mode)) { "a queued transfer has snippets" }
            classify(
                api.send(
                    HttpMethod.POST,
                    listOf("api", "canvases", op.sourceCanvasId.value, "snippets", "transfer"),
                    request,
                    TransferRequest.serializer(),
                    TransferResponse.serializer(),
                ),
            ) { OpResult.Transferred(it) }
        }

        is SyncOp.CreateTag -> createTag(op)
        is SyncOp.UpdateTag -> classify(
            api.send(HttpMethod.PUT, listOf("api", "tags", op.id.value), TagUpdateRequest(op.name, op.color, op.baseVersion), TagUpdateRequest.serializer(), UserTag.serializer()),
        ) { OpResult.TagSaved(it) }
        is SyncOp.DeleteTag -> classify(api.delete(listOf("api", "tags", op.id.value), base(op.baseVersion)), isDelete = true) { OpResult.Done }

        is SyncOp.AddWord -> {
            val ops = batch.entries.map { it.op as SyncOp.AddWord }
            classify(
                api.send(
                    HttpMethod.POST,
                    listOf("api", "word-bank"),
                    WordBankSyncAddRequest(ops.map { NewBankWord(it.text, it.id) }, op.tagIds),
                    WordBankSyncAddRequest.serializer(),
                    WordBankAddResponse.serializer(),
                ),
            ) { OpResult.WordsAdded(it) }
        }
        is SyncOp.RenameWord -> classify(
            api.send(HttpMethod.PUT, listOf("api", "word-bank", op.id.value), WordBankRenameRequest(op.text, op.baseVersion), WordBankRenameRequest.serializer(), BankWord.serializer()),
        ) { OpResult.WordsSaved(listOf(it)) }
        is SyncOp.DeleteWord -> {
            val ops = batch.entries.map { it.op as SyncOp.DeleteWord }
            classify(
                api.sendNoContent(
                    HttpMethod.POST,
                    listOf("api", "word-bank", "delete"),
                    WordBankDeleteRequest(ops.map { it.id }, ops.associate { it.id.value to it.baseVersion }),
                    WordBankDeleteRequest.serializer(),
                ),
                isDelete = true,
            ) { OpResult.Done }
        }
        is SyncOp.WordTag -> {
            val ops = batch.entries.map { it.op as SyncOp.WordTag }
            classify(
                api.send(
                    HttpMethod.POST,
                    listOf("api", "word-bank", "tags"),
                    WordBankTagRequest(ops.map { it.id }, op.tagId, op.op, ops.associate { it.id.value to it.baseVersion }),
                    WordBankTagRequest.serializer(),
                    WordBankTagResponse.serializer(),
                ),
            ) { OpResult.WordsSaved(it.words) }
        }
    }

    private suspend fun putCanvas(id: String, body: CanvasUpdateRequest): SendOutcome = classify(
        api.send(HttpMethod.PUT, listOf("api", "canvases", id), body, CanvasUpdateRequest.serializer(), Canvas.serializer()),
    ) { OpResult.CanvasSaved(it) }

    private suspend fun putSnippet(body: SnippetUpdateRequest, canvasId: String, id: String): SendOutcome = classify(
        api.send(HttpMethod.PUT, listOf("api", "snippets", id), body, SnippetUpdateRequest.serializer(), Snippet.serializer(), query = listOf("canvas_id" to canvasId)),
    ) { OpResult.SnippetSaved(it) }

    /**
     * A name taken on the server (created on another device meanwhile) is the same tag: the
     * local one becomes the server's (the client handles `tag_name_taken` itself).
     */
    private suspend fun createTag(op: SyncOp.CreateTag): SendOutcome {
        val result = api.send(HttpMethod.POST, listOf("api", "tags"), TagCreateRequest(op.name, op.color, op.id), TagCreateRequest.serializer(), UserTag.serializer())
        val error = result.errorOrNull()
        if (error !is ApiError.Server || error.status != 409 || TagErrorCode.parse(error.code) != TagErrorCode.TAG_NAME_TAKEN) {
            return classify(result) { OpResult.TagSaved(it) }
        }
        return when (val tags = api.get(listOf("api", "tags"), ListSerializer(UserTag.serializer()))) {
            is ApiResult.Failure -> classify(tags) { OpResult.Done }
            is ApiResult.Success -> tags.value.firstOrNull { it.name.lowercase(Locale.ROOT) == op.name.lowercase(Locale.ROOT) }
                ?.let { SendOutcome.Ok(OpResult.TagMatched(op.id, it)) }
                ?: SendOutcome.Refused(error)
        }
    }
}
