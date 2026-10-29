package dev.nymph.lyrics.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.nymph.lyrics.app.BuildConfig
import dev.nymph.lyrics.app.status.StatusMonitors
import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.TokenStore
import dev.nymph.lyrics.data.repository.AuthRepository
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.HttpAuthRepository
import dev.nymph.lyrics.data.repository.HttpCanvasRepository
import dev.nymph.lyrics.data.repository.HttpNlpRepository
import dev.nymph.lyrics.data.repository.HttpSnippetRepository
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.data.status.AlertsEndpoint
import dev.nymph.lyrics.data.status.HttpStatusRepository
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

private val Context.authDataStore by preferencesDataStore(name = "auth")

/**
 * Manual dependency graph, one per process (see [dev.nymph.lyrics.app.LyricsApp]). ViewModels
 * receive repositories from here through their factories; tests construct them with fakes.
 */
class AppContainer(
    context: Context,
    baseUrl: String = BuildConfig.API_BASE_URL,
    statusAlertsUrl: String = BuildConfig.STATUS_ALERTS_URL,
) {
    val tokenStore: TokenStore = DataStoreTokenStore(context.applicationContext.authDataStore)

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

    val auth: AuthRepository = HttpAuthRepository(api, tokenStore)
    val canvases: CanvasRepository = HttpCanvasRepository(api)
    val snippets: SnippetRepository = HttpSnippetRepository(api)
    val nlp: NlpRepository = HttpNlpRepository(api)
}
