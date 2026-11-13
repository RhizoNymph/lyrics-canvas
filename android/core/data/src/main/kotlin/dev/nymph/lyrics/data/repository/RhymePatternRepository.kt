package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.error.ApiResult

/** Rhyme patterns across lines (`/api/nlp/rhyme-pattern/detect` and `/suggest`); server-only. */
interface RhymePatternRepository {
    /** The slots where the range's lines rhyme with each other. */
    suspend fun detect(request: PatternDetectRequest): ApiResult<PatternDetectResponse>

    /** Per-slot candidates and whole-line matches for the slots sent. */
    suspend fun suggest(request: PatternSuggestRequest): ApiResult<PatternSuggestResponse>
}

class HttpRhymePatternRepository(private val api: ApiClient) : RhymePatternRepository {
    override suspend fun detect(request: PatternDetectRequest): ApiResult<PatternDetectResponse> = api.send(
        HttpMethod.POST,
        listOf("api", "nlp", "rhyme-pattern", "detect"),
        request,
        PatternDetectRequest.serializer(),
        PatternDetectResponse.serializer(),
    )

    override suspend fun suggest(request: PatternSuggestRequest): ApiResult<PatternSuggestResponse> = api.send(
        HttpMethod.POST,
        listOf("api", "nlp", "rhyme-pattern", "suggest"),
        request,
        PatternSuggestRequest.serializer(),
        PatternSuggestResponse.serializer(),
    )
}
