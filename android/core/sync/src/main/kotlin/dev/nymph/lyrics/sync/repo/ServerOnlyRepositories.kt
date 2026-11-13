package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SearchRepository
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeSuggestResponse
import dev.nymph.lyrics.model.SearchResponse
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.User
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.RecordingStats
import dev.nymph.lyrics.model.voice.VoiceTakeMeta
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import dev.nymph.lyrics.sync.engine.SyncTrigger
import dev.nymph.lyrics.sync.engine.Wake
import dev.nymph.lyrics.sync.engine.Outcomes
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.store.LocalPlacement
import dev.nymph.lyrics.sync.store.LocalSnippet
import dev.nymph.lyrics.sync.store.LocalStore
import dev.nymph.lyrics.sync.store.LocalSearch
import java.io.File

/** Search across canvases always runs on the local copy (same rule, orders and excerpts as the server). */
class LocalSearchRepository(private val local: LocalFirst) : SearchRepository {
    override suspend fun search(query: String, limit: Int): ApiResult<SearchResponse> = local.read { ApiResult.Success(LocalSearch.search(it, query, limit)) }
}

/** Syllabify and rhyme suggestions need the server. */
class GatedNlpRepository(private val http: NlpRepository, private val gate: OnlineGate) : NlpRepository {
    override suspend fun syllabify(text: String): ApiResult<GridData> = gate.call { http.syllabify(text) }

    override suspend fun rhymeSuggest(request: RhymeSuggestRequest): ApiResult<RhymeSuggestResponse> = gate.call { http.rhymeSuggest(request) }
}

/** The answer when a take would build on changes that have not reached the server yet. */
val NOT_SYNCED_YET: ApiError = ApiError.Validation(409, "Wait until this snippet's changes have synced, then record again")

/**
 * Voice takes and recordings need the server. A take goes to the server's copy, so the snippet
 * (and a canvas created offline) must have synced first; the snippet it returns joins the local copy.
 */
class GatedVoiceRepository(
    private val http: VoiceRepository,
    private val gate: OnlineGate,
    private val local: LocalFirst,
) : VoiceRepository {
    override suspend fun uploadTake(canvasId: CanvasId, audio: File, mime: AudioMime, meta: VoiceTakeMeta): ApiResult<VoiceTakeResponse> = gate.call {
        val needs = buildSet {
            add(EntityRef.Canvas(canvasId))
            (meta.target as? VoiceTakeTarget.Append)?.let { add(EntityRef.Snippet(it.snippetId)) }
        }
        if (!local.awaitSettled(needs)) return@call ApiResult.Failure(NOT_SYNCED_YET)
        http.uploadTake(canvasId, audio, mime, meta).also { result ->
            if (result is ApiResult.Success) absorb(local.store, result.value)
        }
    }

    override suspend fun recordings(snippetId: SnippetId): ApiResult<List<Recording>> = gate.call { http.recordings(snippetId) }

    override suspend fun downloadAudio(recordingId: RecordingId, target: File): ApiResult<File> = gate.call { http.downloadAudio(recordingId, target) }

    override suspend fun deleteRecording(recordingId: RecordingId): ApiResult<Unit> = gate.call { http.deleteRecording(recordingId) }

    override suspend fun saveVerification(recordingId: RecordingId, text: String): ApiResult<Recording> = gate.call { http.saveVerification(recordingId, text) }

    override suspend fun clearVerification(recordingId: RecordingId): ApiResult<Recording> = gate.call { http.clearVerification(recordingId) }

    override suspend fun stats(): ApiResult<RecordingStats> = gate.call { http.stats() }

    private suspend fun absorb(store: LocalStore, response: VoiceTakeResponse) {
        val s = response.snippet
        store.update { d ->
            val next = if (d.snippet(s.id) == null) d.withSnippet(LocalSnippet.of(s)).withPlacement(LocalPlacement.of(s)) else Outcomes.absorbSnippet(d, s, rebase = false)
            next to Unit
        }
    }
}

/**
 * Sign-in claims the local copy for the account (another account's copy is deleted); sign-out
 * deletes it with its outbox (the UI warns about unsynced changes first).
 */
class SyncAuthRepository(
    private val http: AuthRepository,
    private val store: LocalStore,
    private val trigger: SyncTrigger,
) : AuthRepository {
    override suspend fun login(email: String, password: String): ApiResult<User> = claim(http.login(email, password))

    override suspend fun register(email: String, password: String, displayName: String): ApiResult<User> =
        claim(http.register(email, password, displayName))

    override suspend fun me(): ApiResult<User> = http.me()

    override suspend fun logout() {
        store.wipe()
        http.logout()
    }

    private suspend fun claim(result: ApiResult<User>): ApiResult<User> {
        if (result is ApiResult.Success) {
            store.claim(result.value.id)
            trigger.wake(Wake.REFRESH)
        }
        return result
    }
}
