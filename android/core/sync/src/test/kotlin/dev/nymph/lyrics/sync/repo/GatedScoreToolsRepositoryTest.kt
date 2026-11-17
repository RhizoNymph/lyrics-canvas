package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.DownloadedFile
import dev.nymph.lyrics.data.repository.ScoreToolsRepository
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreExportFormat
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import dev.nymph.lyrics.sync.Fx
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.store.InMemoryStoreFile
import dev.nymph.lyrics.sync.store.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class GatedScoreToolsRepositoryTest {
    private class NoopTrigger : SyncTrigger {
        val wakes = mutableListOf<Wake>()

        override fun wake(reason: Wake) {
            wakes += reason
        }

        override suspend fun awaitSnapshot(): ApiError? = null
    }

    private class CountingHttp : ScoreToolsRepository {
        var calls = 0
        val score = Score(meters = listOf(ScoreMeter(0, 4, 4)), tempos = listOf(ScoreTempo(0, 90.0)), snap = ScoreSnap.EIGHTH, lines = emptyList())

        override suspend fun draft(snippetId: SnippetId, request: ScoreDraftRequest): ApiResult<ScoreDraftResponse> {
            calls++
            return ApiResult.Success(ScoreDraftResponse(score))
        }

        override suspend fun export(snippetId: SnippetId, format: ScoreExportFormat, target: File): ApiResult<DownloadedFile> {
            calls++
            return ApiResult.Success(DownloadedFile(target, null))
        }
    }

    private val grid = ScoreDraftRequest.FromGrid(ScoreSnap.EIGHTH, DraftMeter(4, 4), 90.0)

    private fun local(trigger: SyncTrigger = NoopTrigger()) = LocalFirst(LocalStore(InMemoryStoreFile(Fx.data())), trigger)

    @Test
    fun `offline both calls answer NeedsConnection without a request`() = runTest {
        val http = CountingHttp()
        val repo = GatedScoreToolsRepository(http, OnlineGate(MutableStateFlow(false)), local())
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), repo.draft(Fx.s1, grid))
        assertEquals(ApiResult.Failure(ApiError.NeedsConnection), repo.export(Fx.s1, ScoreExportFormat.MIDI, File("x.mid")))
        assertEquals(0, http.calls)
    }

    @Test
    fun `online with nothing queued for the snippet the calls pass through`() = runTest {
        val http = CountingHttp()
        val repo = GatedScoreToolsRepository(http, OnlineGate.always(), local())
        assertTrue(repo.draft(Fx.s1, grid) is ApiResult.Success)
        assertTrue(repo.export(Fx.s1, ScoreExportFormat.MUSICXML, File("x.musicxml")) is ApiResult.Success)
        assertEquals(2, http.calls)
    }

    @Test
    fun `a snippet with unsynced changes is refused until they reach the server`() = runTest {
        val http = CountingHttp()
        val trigger = NoopTrigger()
        val lf = local(trigger)
        LocalSnippetRepository(lf, Fx.edits()).update(Fx.c1, Fx.s1, SnippetUpdateRequest(rawText = "not on the server yet"))
        val repo = GatedScoreToolsRepository(http, OnlineGate.always(), lf)
        assertEquals(ApiResult.Failure(SCORE_NOT_SYNCED_YET), repo.draft(Fx.s1, grid))
        assertEquals(ApiResult.Failure(SCORE_NOT_SYNCED_YET), repo.export(Fx.s1, ScoreExportFormat.MIDI, File("x.mid")))
        assertEquals(0, http.calls)
        assertTrue(Wake.EDIT in trigger.wakes)
    }
}
