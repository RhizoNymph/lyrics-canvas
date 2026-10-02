package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.SyllabifyRequest
import dev.nymph.lyrics.model.error.ApiResult

interface NlpRepository {
    /** Server syllabification of multi-line text into a beat grid. */
    suspend fun syllabify(text: String): ApiResult<GridData>

    suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse>
}

class HttpNlpRepository(private val api: ApiClient) : NlpRepository {
    override suspend fun syllabify(text: String): ApiResult<GridData> =
        api.send(HttpMethod.POST, listOf("api", "nlp", "syllabify"), SyllabifyRequest(text), SyllabifyRequest.serializer(), GridData.serializer())

    override suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse> = api.send(
        HttpMethod.POST,
        listOf("api", "nlp", "rhyme-suggest"),
        request,
        RhymeSuggestRequest.serializer(),
        RhymeSuggestResponse.serializer(),
    )
}
