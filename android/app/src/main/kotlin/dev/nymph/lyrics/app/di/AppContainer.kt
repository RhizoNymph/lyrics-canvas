package dev.nymph.lyrics.app.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.nymph.lyrics.app.BuildConfig
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
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

private val Context.authDataStore by preferencesDataStore(name = "auth")

/**
 * Manual dependency graph, one per process (see [dev.nymph.lyrics.app.LyricsApp]). ViewModels
 * receive repositories from here through their factories; tests construct them with fakes.
 */
class AppContainer(context: Context, baseUrl: String = BuildConfig.API_BASE_URL) {
    val tokenStore: TokenStore = DataStoreTokenStore(context.applicationContext.authDataStore)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: ApiClient = requireNotNull(ApiClient.create(baseUrl, tokenStore, okHttp)) {
        "API_BASE_URL is not an http(s) URL: $baseUrl"
    }

    val auth: AuthRepository = HttpAuthRepository(api, tokenStore)
    val canvases: CanvasRepository = HttpCanvasRepository(api)
    val snippets: SnippetRepository = HttpSnippetRepository(api)
    val nlp: NlpRepository = HttpNlpRepository(api)
}
