package dev.nymph.lyrics.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.nymph.lyrics.app.BuildConfig
import dev.nymph.lyrics.app.status.StatusMonitors
import dev.nymph.lyrics.app.sync.SyncGraph
import dev.nymph.lyrics.sync.SyncCenter
import dev.nymph.lyrics.sync.repo.CachedTagPresetRepository
import dev.nymph.lyrics.sync.repo.GatedNlpRepository
import dev.nymph.lyrics.sync.repo.GatedVoiceRepository
import dev.nymph.lyrics.sync.repo.LocalCanvasRepository
import dev.nymph.lyrics.sync.repo.LocalSearchRepository
import dev.nymph.lyrics.sync.repo.LocalSnippetRepository
import dev.nymph.lyrics.sync.repo.LocalTagRepository
import dev.nymph.lyrics.sync.repo.LocalWordBankRepository
import dev.nymph.lyrics.sync.repo.SyncAuthRepository
import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.TokenStore
import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.HttpAuthRepository
import dev.nymph.lyrics.data.repository.HttpNlpRepository
import dev.nymph.lyrics.data.repository.SearchRepository
import dev.nymph.lyrics.app.canvas.search.CanvasSearchMemory
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.data.repository.HttpTagPresetRepository
import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.data.repository.TagRepository
import dev.nymph.lyrics.app.tags.DataStoreTagFilterStore
import dev.nymph.lyrics.app.tags.FilterTextStore
import dev.nymph.lyrics.app.wordbank.DataStoreWordBankFilterStore
import dev.nymph.lyrics.data.repository.HttpWordBankRepository
import dev.nymph.lyrics.data.repository.WordBankRepository
import dev.nymph.lyrics.app.tags.TagFilterStore
import dev.nymph.lyrics.data.status.AlertsEndpoint
import dev.nymph.lyrics.data.status.HttpStatusRepository
import dev.nymph.lyrics.data.repository.HttpVoiceRepository
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.app.voice.DataStoreVoiceSettings
import dev.nymph.lyrics.app.voice.VoiceSettingsStore
import dev.nymph.lyrics.app.voice.recorder.AudioRecorder
import dev.nymph.lyrics.app.voice.recorder.MediaRecorderAudioRecorder
import dev.nymph.lyrics.app.voice.recordings.MediaAudioPlayer
import dev.nymph.lyrics.app.voice.recordings.RecordingUpdates
import dev.nymph.lyrics.app.voice.recordings.SeekableAudioPlayer
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

private val Context.authDataStore by preferencesDataStore(name = "auth")
private val Context.voiceDataStore by preferencesDataStore(name = "voice")
private val Context.tagFilterDataStore by preferencesDataStore(name = "tag_filters")

/**
 * Manual dependency graph, one per process (see [dev.nymph.lyrics.app.LyricsApp]). ViewModels
 * receive repositories from here through their factories; tests construct them with fakes.
 */
class AppContainer(
    context: Context,
    baseUrl: String = BuildConfig.API_BASE_URL,
    statusAlertsUrl: String = BuildConfig.STATUS_ALERTS_URL,
) {
    private val appContext: Context = context.applicationContext

    val tokenStore: TokenStore = DataStoreTokenStore(appContext.authDataStore)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiBase = requireNotNull(baseUrl.toHttpUrlOrNull()) { "API_BASE_URL is not an http(s) URL: $baseUrl" }

    /** Health probes and alert fetches give up quickly: a hung server should show as down soon. */
    private val statusHttp = okHttp.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    private val alertsEndpoint = requireNotNull(AlertsEndpoint.resolve(statusAlertsUrl, apiBase)) {
        "STATUS_ALERTS_URL is not blank, \"${AlertsEndpoint.DISABLED_VALUE}\" or an http(s) URL: $statusAlertsUrl"
    }

    /** Server health and cluster alerts, shown by the app-wide status banner. */
    val status = StatusMonitors(HttpStatusRepository(apiBase, alertsEndpoint, statusHttp))

    /** Every regular call also reports to the health monitor (502/503/504 and IO errors mark it suspect). */
    private val api = ApiClient(apiBase, tokenStore, okHttp, observer = status.health)

    /**
     * The offline copy and the sync engine (docs/features/android_offline_sync.md). Every
     * offline-capable repository below reads and writes the copy; server-only ones go through
     * its gate (NeedsConnection while the server is down).
     */
    val sync: SyncGraph = SyncGraph(appContext.filesDir, api, status.health.state, ProcessScope.scope)

    /** Sync status, "Not synced" badges, the conflict review and alerts. */
    val syncCenter: SyncCenter get() = sync.center

    /** Sign-in claims the local copy for the account; sign-out deletes it. */
    val auth: AuthRepository = SyncAuthRepository(HttpAuthRepository(api, tokenStore), sync.store, sync.engine)
    val canvases: CanvasRepository = LocalCanvasRepository(sync.local, sync.edits)

    /** Canvases renamed or created away from the canvas list, for the list on the back stack. */
    val canvasUpdates: CanvasUpdates = CanvasUpdates()

    /** The in-canvas search text per canvas, for this process only. */
    val canvasSearches: CanvasSearchMemory = CanvasSearchMemory()

    /** Search across canvases, always on the local copy (the server's matching rule). */
    val search: SearchRepository = LocalSearchRepository(sync.local)
    val snippets: SnippetRepository = LocalSnippetRepository(sync.local, sync.edits)
    val nlp: NlpRepository = GatedNlpRepository(HttpNlpRepository(api), sync.gate)
    val tags: TagRepository = LocalTagRepository(sync.local, sync.edits)

    /** Read from the copy; saved online only. */
    val tagPresets: TagPresetRepository = CachedTagPresetRepository(sync.local, HttpTagPresetRepository(api), sync.gate)

    /** The canvas filter bar's text, per canvas, on this device. */
    val tagFilters: TagFilterStore = DataStoreTagFilterStore(appContext.tagFilterDataStore)

    val wordBank: WordBankRepository = LocalWordBankRepository(sync.local, sync.edits, HttpWordBankRepository(api), sync.gate)

    /** The word bank filter bar's text (user-wide on this device; key `word_bank_filter`). */
    val wordBankFilter: FilterTextStore = DataStoreWordBankFilterStore(appContext.tagFilterDataStore)

    /**
     * Voice takes wait for transcription (the backend allows the STT service 120 s), and upload
     * up to 25 MiB, so they get their own, longer timeouts.
     */
    private val voiceHttp = okHttp.newBuilder()
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    val voice: VoiceRepository = GatedVoiceRepository(HttpVoiceRepository(ApiClient(apiBase, tokenStore, voiceHttp, observer = status.health)), sync.gate, sync.local)

    /** Silence auto-stop, "Use word bank as hints" and other per-device voice settings (DataStore `voice`). */
    val voiceSettings: VoiceSettingsStore = DataStoreVoiceSettings(appContext.voiceDataStore)

    /** Downloaded recording audio, played from here. */
    val recordingsCacheDir: File = File(appContext.cacheDir, "recordings")

    /** A recorder writing takes into the app cache (one per voice modal). */
    fun newRecorder(): AudioRecorder = MediaRecorderAudioRecorder(appContext, File(appContext.cacheDir, "voice-takes"))

    fun newAudioPlayer(): SeekableAudioPlayer = MediaAudioPlayer()

    /** Recordings changed on the Check transcript screen, for the editor's list. */
    val recordingUpdates: RecordingUpdates = RecordingUpdates()
}
