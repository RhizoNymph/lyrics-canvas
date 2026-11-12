package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.WordBankRepository
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankFamiliesResponse
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankRhymesResponse
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.RelatedWordsResponse
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.store.Views
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The word bank: words and their tags local-first; rhymes, families and related words are
 * server-only (`NeedsConnection` offline).
 */
class LocalWordBankRepository(
    private val local: LocalFirst,
    private val edits: LocalEdits,
    private val http: WordBankRepository,
    private val gate: OnlineGate,
) : WordBankRepository {
    override suspend fun list(): ApiResult<List<BankWord>> = local.read { ApiResult.Success(Views.words(it)) }

    override suspend fun add(words: List<String>, tagIds: List<TagId>): ApiResult<WordBankAddResponse> = local.edit { edits.addWords(it, words, tagIds) }

    override suspend fun rename(id: BankWordId, text: String): ApiResult<BankWord> = local.edit { edits.renameWord(it, id, text) }

    override suspend fun delete(ids: List<BankWordId>): ApiResult<Unit> = local.edit { edits.deleteWords(it, ids) }

    override suspend fun setTag(ids: List<BankWordId>, tag: TagId, op: TagLinkOp): ApiResult<List<BankWord>> = local.edit { edits.setWordTag(it, ids, tag, op) }

    override suspend fun rhymes(request: BankRhymesRequest): ApiResult<BankRhymesResponse> = gate.call { http.rhymes(request) }

    override suspend fun families(request: BankFamiliesRequest): ApiResult<BankFamiliesResponse> = gate.call { http.families(request) }

    override suspend fun related(query: String, limit: Int): ApiResult<RelatedWordsResponse> = gate.call { http.related(query, limit) }

    override fun observeWords(): Flow<List<BankWord>> = local.store.data.map(Views::words).distinctUntilChanged()
}
