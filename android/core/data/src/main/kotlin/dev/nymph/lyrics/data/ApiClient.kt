package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/**
 * JSON-over-HTTP client for the Lyrics Canvas backend.
 *
 * Every call returns an [ApiResult]; no exception escapes except coroutine cancellation.
 * Requests carry `Authorization: Bearer <token>` when [tokenStore] has a token, and a 401 clears
 * it (the web client's interceptor does the same). Paths are given as literal segments, each
 * percent-encoded onto [baseUrl].
 */
class ApiClient(
    private val baseUrl: HttpUrl,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = LyricsJson,
) {
    suspend fun <T> get(path: List<String>, response: DeserializationStrategy<T>): ApiResult<T> =
        call(HttpMethod.GET, path, null) { json.decodeFromString(response, it) }

    suspend fun <B, T> send(
        method: HttpMethod,
        path: List<String>,
        body: B,
        bodySerializer: SerializationStrategy<B>,
        response: DeserializationStrategy<T>,
    ): ApiResult<T> = call(method, path, json.encodeToString(bodySerializer, body)) { json.decodeFromString(response, it) }

    /** DELETE; the 204 body is ignored. */
    suspend fun delete(path: List<String>): ApiResult<Unit> = call(HttpMethod.DELETE, path, null) { }

    private suspend fun <T> call(method: HttpMethod, path: List<String>, body: String?, decode: (String) -> T): ApiResult<T> {
        val request = buildRequest(method, path, body, tokenStore.token())
        val response = try {
            okHttpClient.newCall(request).await()
        } catch (e: IOException) {
            return ApiResult.Failure(ApiError.Network(e))
        }
        val result = withContext(ioDispatcher) { response.use { read(it, decode) } }
        val error = result.errorOrNull()
        if (error is ApiError.Unauthorized && error.status == 401) tokenStore.clear()
        return result
    }

    private fun buildRequest(method: HttpMethod, path: List<String>, body: String?, token: String?): Request {
        val url = baseUrl.newBuilder().apply { path.forEach(::addPathSegment) }.build()
        return Request.Builder()
            .url(url)
            .method(method.name, body?.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
    }

    private fun <T> read(response: Response, decode: (String) -> T): ApiResult<T> {
        val text = try {
            response.body.string()
        } catch (e: IOException) {
            return ApiResult.Failure(ApiError.Network(e))
        }
        if (!response.isSuccessful) return ApiResult.Failure(ApiError.fromHttp(response.code, text))
        return try {
            ApiResult.Success(decode(text))
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.Decoding(e))
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure(ApiError.Decoding(e))
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** A client for [baseUrl] (e.g. `http://10.0.2.2:8000`), or null when it is not an http(s) URL. */
        fun create(
            baseUrl: String,
            tokenStore: TokenStore,
            okHttpClient: OkHttpClient,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ApiClient? = baseUrl.toHttpUrlOrNull()?.let { ApiClient(it, tokenStore, okHttpClient, ioDispatcher) }
    }
}

/** Enqueue the call and suspend until it answers; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
