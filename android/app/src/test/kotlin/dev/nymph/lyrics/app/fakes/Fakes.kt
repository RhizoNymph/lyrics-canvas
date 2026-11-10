package dev.nymph.lyrics.app.fakes

import dev.nymph.lyrics.data.TokenStore
import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.model.BatchPositionResponse
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasUpdateRequest
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.MergeResponse
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.RemovePlacementResponse
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult

val UNAUTHORIZED = ApiError.Unauthorized(401, "Could not validate credentials")

/**
 * Mirrors ApiClient's 401 rule: a 401 clears the token store. Fakes return through this so a
 * test can observe the sign-out the real client would cause.
 */
suspend fun <T> TokenStore?.answer(result: ApiResult<T>): ApiResult<T> {
    if (result is ApiResult.Failure && result.error is ApiError.Unauthorized && (result.error as ApiError.Unauthorized).status == 401) {
        this?.clear()
    }
    return result
}

class FakeCanvasRepository(
    var detail: ApiResult<CanvasDetail>,
    private val tokenStore: TokenStore? = null,
) : CanvasRepository {
    var listResult: ApiResult<List<Canvas>> = ApiResult.Success(emptyList())
    var createResult: (String) -> ApiResult<Canvas> = { ApiResult.Success(canvas("new", it)) }
    val created = mutableListOf<String>()
    val updates = mutableListOf<Pair<CanvasId, CanvasUpdateRequest>>()
    var updateResult: (CanvasId, CanvasUpdateRequest) -> ApiResult<Canvas> = { id, update -> ApiResult.Success(canvas(id.value, update.title ?: "Song")) }
    var listCalls = 0

    override suspend fun list(): ApiResult<List<Canvas>> {
        listCalls++
        return tokenStore.answer(listResult)
    }

    override suspend fun create(title: String): ApiResult<Canvas> {
        created += title
        return tokenStore.answer(createResult(title))
    }

    override suspend fun get(id: CanvasId): ApiResult<CanvasDetail> = tokenStore.answer(detail)

    override suspend fun update(id: CanvasId, update: CanvasUpdateRequest): ApiResult<Canvas> {
        updates += id to update
        return tokenStore.answer(updateResult(id, update))
    }
}

class FakeSnippetRepository(private val tokenStore: TokenStore? = null) : SnippetRepository {
    val created = mutableListOf<SnippetCreateRequest>()
    /** Placement deletes (plain Delete / Remove from this canvas). */
    val removed = mutableListOf<SnippetId>()
    val removedFrom = mutableListOf<CanvasId>()
    val deletedEverywhere = mutableListOf<SnippetId>()
    val batches = mutableListOf<List<PositionUpdate>>()
    val batchCanvases = mutableListOf<CanvasId>()
    val merges = mutableListOf<MergeRequest>()
    val transfers = mutableListOf<Pair<CanvasId, TransferRequest>>()

    var createResult: (SnippetCreateRequest) -> ApiResult<Snippet> = { body ->
        ApiResult.Success(snippet("new${created.size}", rawText = body.rawText ?: "", x = body.positionX, y = body.positionY))
    }
    var removeResult: (SnippetId) -> ApiResult<RemovePlacementResponse> = { ApiResult.Success(RemovePlacementResponse(snippetDeleted = true)) }
    var deleteEverywhereResult: (SnippetId) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    var batchResult: ApiResult<BatchPositionResponse> = ApiResult.Success(BatchPositionResponse(1))
    var mergeResult: (MergeRequest) -> ApiResult<MergeResponse> = { ApiResult.Failure(ApiError.Server(500, "no merge")) }
    var transferResult: (TransferRequest) -> ApiResult<TransferResponse> = { request ->
        ApiResult.Success(TransferResponse(request.targetCanvasId, request.snippetIds, emptyList(), removedFromSource = request.mode == dev.nymph.lyrics.model.TransferMode.MOVE))
    }

    override suspend fun create(canvasId: CanvasId, body: SnippetCreateRequest): ApiResult<Snippet> {
        created += body
        return tokenStore.answer(createResult(body))
    }

    override suspend fun update(canvasId: CanvasId, id: SnippetId, body: SnippetUpdateRequest): ApiResult<Snippet> =
        ApiResult.Failure(ApiError.Server(500, "not used"))

    override suspend fun removeFromCanvas(canvasId: CanvasId, id: SnippetId): ApiResult<RemovePlacementResponse> {
        removed += id
        removedFrom += canvasId
        return tokenStore.answer(removeResult(id))
    }

    override suspend fun deleteEverywhere(id: SnippetId): ApiResult<Unit> {
        deletedEverywhere += id
        return tokenStore.answer(deleteEverywhereResult(id))
    }

    override suspend fun batchPosition(canvasId: CanvasId, updates: List<PositionUpdate>): ApiResult<BatchPositionResponse> {
        batches += updates
        batchCanvases += canvasId
        return tokenStore.answer(batchResult)
    }

    override suspend fun merge(canvasId: CanvasId, request: MergeRequest): ApiResult<MergeResponse> {
        merges += request
        return tokenStore.answer(mergeResult(request))
    }

    override suspend fun transfer(sourceCanvasId: CanvasId, request: TransferRequest): ApiResult<TransferResponse> {
        transfers += sourceCanvasId to request
        return tokenStore.answer(transferResult(request))
    }
}

/** Answers rhyme queries through [handler], which may suspend (to test cancellation / ordering). */
class FakeNlpRepository(
    var handler: suspend (RhymeSuggestRequest) -> ApiResult<RhymeSuggestResponse> = { ApiResult.Success(response()) },
) : NlpRepository {
    val requests = mutableListOf<RhymeSuggestRequest>()
    val completed = mutableListOf<RhymeSuggestRequest>()

    override suspend fun syllabify(text: String): ApiResult<GridData> = ApiResult.Success(GridData())

    override suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse> {
        requests += request
        val result = handler(request)
        completed += request
        return result
    }
}

class FakeAuthRepository(private val tokenStore: TokenStore) : AuthRepository {
    var loginResult: ApiResult<User> = ApiResult.Success(USER)
    var registerResult: ApiResult<User> = ApiResult.Success(USER)
    val logins = mutableListOf<Pair<String, String>>()
    val registrations = mutableListOf<Triple<String, String, String>>()

    override suspend fun login(email: String, password: String): ApiResult<User> {
        logins += email to password
        if (loginResult is ApiResult.Success) tokenStore.save("token")
        return loginResult
    }

    override suspend fun register(email: String, password: String, displayName: String): ApiResult<User> {
        registrations += Triple(email, password, displayName)
        if (registerResult is ApiResult.Success) tokenStore.save("token")
        return registerResult
    }

    override suspend fun me(): ApiResult<User> = ApiResult.Success(USER)

    override suspend fun logout() = tokenStore.clear()
}
