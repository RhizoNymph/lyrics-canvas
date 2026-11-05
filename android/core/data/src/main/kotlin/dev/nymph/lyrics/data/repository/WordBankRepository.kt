package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankFamiliesResponse
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankRhymesResponse
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.RelatedWordsResponse
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.WordBankAddRequest
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.WordBankDeleteRequest
import dev.nymph.lyrics.model.WordBankRenameRequest
import dev.nymph.lyrics.model.WordBankTagRequest
import dev.nymph.lyrics.model.WordBankTagResponse
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.map
import kotlinx.serialization.builtins.ListSerializer

/** The user's word bank (user-wide; docs/features/word_bank.md). Every call is scoped to the user. */
interface WordBankRepository {
    /** `GET /api/word-bank`: the whole bank, newest first (clients filter locally). */
    suspend fun list(): ApiResult<List<BankWord>>

    /** `POST /api/word-bank`: add [words] (already validated) and link [tagIds] to each. */
    suspend fun add(words: List<String>, tagIds: List<TagId> = emptyList()): ApiResult<WordBankAddResponse>

    /** `PUT /api/word-bank/{id}`. */
    suspend fun rename(id: BankWordId, text: String): ApiResult<BankWord>

    /** `POST /api/word-bank/delete` (204). */
    suspend fun delete(ids: List<BankWordId>): ApiResult<Unit>

    /** `POST /api/word-bank/tags`: link or unlink one tag on several words; the updated words. */
    suspend fun setTag(ids: List<BankWordId>, tag: TagId, op: TagLinkOp): ApiResult<List<BankWord>>

    /** `POST /api/word-bank/rhymes`. */
    suspend fun rhymes(request: BankRhymesRequest): ApiResult<BankRhymesResponse>

    /** `POST /api/word-bank/families`. */
    suspend fun families(request: BankFamiliesRequest): ApiResult<BankFamiliesResponse>

    /** `GET /api/word-bank/related?q=&limit=`. */
    suspend fun related(query: String, limit: Int = DEFAULT_RELATED_LIMIT): ApiResult<RelatedWordsResponse>

    companion object {
        const val DEFAULT_RELATED_LIMIT: Int = 30
    }
}

class HttpWordBankRepository(private val api: ApiClient) : WordBankRepository {
    private val base = listOf("api", "word-bank")

    override suspend fun list(): ApiResult<List<BankWord>> = api.get(base, ListSerializer(BankWord.serializer()))

    override suspend fun add(words: List<String>, tagIds: List<TagId>): ApiResult<WordBankAddResponse> =
        api.send(HttpMethod.POST, base, WordBankAddRequest(words, tagIds), WordBankAddRequest.serializer(), WordBankAddResponse.serializer())

    override suspend fun rename(id: BankWordId, text: String): ApiResult<BankWord> =
        api.send(HttpMethod.PUT, base + id.value, WordBankRenameRequest(text), WordBankRenameRequest.serializer(), BankWord.serializer())

    override suspend fun delete(ids: List<BankWordId>): ApiResult<Unit> =
        api.sendNoContent(HttpMethod.POST, base + "delete", WordBankDeleteRequest(ids), WordBankDeleteRequest.serializer())

    override suspend fun setTag(ids: List<BankWordId>, tag: TagId, op: TagLinkOp): ApiResult<List<BankWord>> = api.send(
        HttpMethod.POST,
        base + "tags",
        WordBankTagRequest(ids, tag, op),
        WordBankTagRequest.serializer(),
        WordBankTagResponse.serializer(),
    ).map { it.words }

    override suspend fun rhymes(request: BankRhymesRequest): ApiResult<BankRhymesResponse> =
        api.send(HttpMethod.POST, base + "rhymes", request, BankRhymesRequest.serializer(), BankRhymesResponse.serializer())

    override suspend fun families(request: BankFamiliesRequest): ApiResult<BankFamiliesResponse> =
        api.send(HttpMethod.POST, base + "families", request, BankFamiliesRequest.serializer(), BankFamiliesResponse.serializer())

    override suspend fun related(query: String, limit: Int): ApiResult<RelatedWordsResponse> =
        api.sendQuery(HttpMethod.GET, base + "related", listOf("q" to query, "limit" to limit.toString()), RelatedWordsResponse.serializer())
}
