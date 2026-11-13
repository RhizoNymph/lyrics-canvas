package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.RhymePatternRepository
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.PatternDetectRequest
import dev.nymph.lyrics.model.PatternDetectResponse
import dev.nymph.lyrics.model.PatternSuggestRequest
import dev.nymph.lyrics.model.PatternSuggestResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SpanRef
import dev.nymph.lyrics.model.SlotSpec
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatedRhymePatternRepositoryTest {
    private val detect = PatternDetectRequest(CanvasId("c1"), SnippetId("s1"), 0, 2)
    private val suggest = PatternSuggestRequest(CanvasId("c1"), SnippetId("s1"), 0, 2, listOf(SlotSpec("s0", listOf(SpanRef(0, 1, 2)))))

    @Test
    fun `pattern calls answer NeedsConnection while offline and pass through online`() = runTest {
        val online = MutableStateFlow(false)
        var calls = 0
        val repo = GatedRhymePatternRepository(
            object : RhymePatternRepository {
                override suspend fun detect(request: PatternDetectRequest): ApiResult<PatternDetectResponse> =
                    ApiResult.Success(PatternDetectResponse(emptyList(), emptyList(), 1.0)).also { calls++ }

                override suspend fun suggest(request: PatternSuggestRequest): ApiResult<PatternSuggestResponse> =
                    ApiResult.Success(PatternSuggestResponse(emptyList(), emptyList(), 0, 1.0)).also { calls++ }
            },
            OnlineGate(online),
        )
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), repo.detect(detect))
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), repo.suggest(suggest))
        assertEquals(0, calls)
        online.value = true
        assertTrue(repo.detect(detect) is ApiResult.Success)
        assertTrue(repo.suggest(suggest) is ApiResult.Success)
        assertEquals(2, calls)
    }
}
