package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.RhymePatternRepository
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.error.ApiResult

/** Rhyme patterns need the server: `NeedsConnection` at once while it is unreachable. */
class GatedRhymePatternRepository(private val http: RhymePatternRepository, private val gate: OnlineGate) : RhymePatternRepository {
    override suspend fun detect(request: PatternDetectRequest): ApiResult<PatternDetectResponse> = gate.call { http.detect(request) }

    override suspend fun suggest(request: PatternSuggestRequest): ApiResult<PatternSuggestResponse> = gate.call { http.suggest(request) }
}
