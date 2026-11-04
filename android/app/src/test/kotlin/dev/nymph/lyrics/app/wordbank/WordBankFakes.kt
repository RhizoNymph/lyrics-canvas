package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.data.repository.WordBankRepository
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankFamiliesResponse
import dev.nymph.lyrics.model.BankRhymeAnchorInfo
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankRhymesResponse
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.RelatedWordsResponse
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.error.ApiResult

fun bankWord(text: String, vararg tags: Tag, id: String = "w-$text"): BankWord = BankWord(BankWordId(id), text, tags.toList(), "2026-11-04")

/** An in-memory bank: add, rename, delete and tag behave like the server's happy paths unless overridden. */
class FakeWordBankRepository(var words: List<BankWord> = emptyList()) : WordBankRepository {
    var listResult: () -> ApiResult<List<BankWord>> = { ApiResult.Success(words) }
    var addResult: ((List<String>, List<TagId>) -> ApiResult<WordBankAddResponse>)? = null
    var renameResult: ((BankWordId, String) -> ApiResult<BankWord>)? = null
    var deleteResult: (List<BankWordId>) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    var setTagResult: ((List<BankWordId>, TagId, TagLinkOp) -> ApiResult<List<BankWord>>)? = null
    var rhymesResult: (BankRhymesRequest) -> ApiResult<BankRhymesResponse> = { ApiResult.Success(BankRhymesResponse(BankRhymeAnchorInfo("x"))) }
    var familiesResult: (BankFamiliesRequest) -> ApiResult<BankFamiliesResponse> = { ApiResult.Success(BankFamiliesResponse()) }
    var relatedResults: ArrayDeque<ApiResult<RelatedWordsResponse>> = ArrayDeque()

    /** Tags the fake knows by id, for links. */
    var knownTags: Map<TagId, Tag> = emptyMap()

    val adds = mutableListOf<Pair<List<String>, List<TagId>>>()
    val renames = mutableListOf<Pair<BankWordId, String>>()
    val deletes = mutableListOf<List<BankWordId>>()
    val tagCalls = mutableListOf<Triple<List<BankWordId>, TagId, TagLinkOp>>()
    val rhymeRequests = mutableListOf<BankRhymesRequest>()
    val familyRequests = mutableListOf<BankFamiliesRequest>()
    val relatedQueries = mutableListOf<String>()

    override suspend fun list(): ApiResult<List<BankWord>> = listResult()

    override suspend fun add(words: List<String>, tagIds: List<TagId>): ApiResult<WordBankAddResponse> {
        adds += words to tagIds
        addResult?.let { return it(words, tagIds) }
        val created = mutableListOf<BankWord>()
        val existing = mutableListOf<BankWord>()
        val tags = tagIds.mapNotNull { knownTags[it] }
        for (w in words) {
            val have = this.words.firstOrNull { it.text.equals(w, ignoreCase = true) }
            if (have != null) {
                val linked = have.copy(tags = (have.tags + tags).distinctBy { it.id })
                this.words = this.words.map { if (it.id == have.id) linked else it }
                existing += linked
            } else {
                val made = BankWord(BankWordId("w-$w"), w, tags, "2026-11-05")
                this.words = listOf(made) + this.words
                created += made
            }
        }
        return ApiResult.Success(WordBankAddResponse(created, existing))
    }

    override suspend fun rename(id: BankWordId, text: String): ApiResult<BankWord> {
        renames += id to text
        renameResult?.let { return it(id, text) }
        val renamed = words.first { it.id == id }.copy(text = text)
        words = words.map { if (it.id == id) renamed else it }
        return ApiResult.Success(renamed)
    }

    override suspend fun delete(ids: List<BankWordId>): ApiResult<Unit> {
        deletes += ids
        val result = deleteResult(ids)
        if (result is ApiResult.Success) words = words.filter { it.id !in ids }
        return result
    }

    override suspend fun setTag(ids: List<BankWordId>, tag: TagId, op: TagLinkOp): ApiResult<List<BankWord>> {
        tagCalls += Triple(ids, tag, op)
        setTagResult?.let { return it(ids, tag, op) }
        val t = knownTags.getValue(tag)
        val updated = words.filter { it.id in ids }.map { w ->
            when (op) {
                TagLinkOp.LINK -> w.copy(tags = (w.tags + t).distinctBy { it.id })
                TagLinkOp.UNLINK -> w.copy(tags = w.tags.filter { it.id != tag })
            }
        }
        words = words.map { w -> updated.firstOrNull { it.id == w.id } ?: w }
        return ApiResult.Success(updated)
    }

    override suspend fun rhymes(request: BankRhymesRequest): ApiResult<BankRhymesResponse> {
        rhymeRequests += request
        return rhymesResult(request)
    }

    override suspend fun families(request: BankFamiliesRequest): ApiResult<BankFamiliesResponse> {
        familyRequests += request
        return familiesResult(request)
    }

    override suspend fun related(query: String, limit: Int): ApiResult<RelatedWordsResponse> {
        relatedQueries += query
        return relatedResults.removeFirstOrNull() ?: ApiResult.Success(RelatedWordsResponse(query))
    }
}

class FakeWordBankCanvasPort : WordBankCanvasPort {
    var result: (String, SnippetType) -> DraftCreateResult = { text, type -> DraftCreateResult.Created(snippet("new", rawText = text, type = type)) }
    val created = mutableListOf<Pair<String, SnippetType>>()

    override suspend fun createSnippetFromText(text: String, type: SnippetType): DraftCreateResult {
        created += text to type
        return result(text, type)
    }
}
