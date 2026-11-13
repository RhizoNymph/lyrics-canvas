package dev.nymph.lyrics.data

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
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
 * percent-encoded onto [baseUrl]. Every completed call is also reported to [observer] as a
 * [dev.nymph.lyrics.model.status.HealthSignal] (see [healthSignalOf]); results are unchanged.
 */
class ApiClient(
    private val baseUrl: HttpUrl,
    private val tokenStore: TokenStore,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = LyricsJson,
    private val observer: ApiCallObserver = ApiCallObserver.None,
) {
    suspend fun <T> get(path: List<String>, response: DeserializationStrategy<T>): ApiResult<T> =
        call(HttpMethod.GET, path, null) { readJson(it) { text -> json.decodeFromString(response, text) } }

    /** A JSON-body call; [query] parameters (e.g. `PUT /api/snippets/{id}?canvas_id=`) go on the URL. */
    suspend fun <B, T> send(
        method: HttpMethod,
        path: List<String>,
        body: B,
        bodySerializer: SerializationStrategy<B>,
        response: DeserializationStrategy<T>,
        query: List<Pair<String, String>> = emptyList(),
    ): ApiResult<T> = call(method, path, json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA_TYPE), query = query) {
        readJson(it) { text -> json.decodeFromString(response, text) }
    }

    /** A JSON-body call whose response (e.g. a 204) is ignored. */
    suspend fun <B> sendNoContent(
        method: HttpMethod,
        path: List<String>,
        body: B,
        bodySerializer: SerializationStrategy<B>,
    ): ApiResult<Unit> = call(method, path, json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA_TYPE)) { readJson(it) { } }

    /** POST a multipart form (file uploads); the JSON response is decoded with [response]. */
    suspend fun <T> sendMultipart(path: List<String>, body: MultipartBody, response: DeserializationStrategy<T>): ApiResult<T> =
        call(HttpMethod.POST, path, body) { readJson(it) { text -> json.decodeFromString(response, text) } }

    /**
     * A bodiless [method] call with [query] parameters (e.g. `POST /api/snippets/{id}/tags?tag_id=`);
     * the JSON response is decoded with [response].
     */
    suspend fun <T> sendQuery(
        method: HttpMethod,
        path: List<String>,
        query: List<Pair<String, String>>,
        response: DeserializationStrategy<T>,
    ): ApiResult<T> {
        val body = if (method == HttpMethod.GET || method == HttpMethod.DELETE) null else ByteArray(0).toRequestBody(null)
        return call(method, path, body, query = query) { readJson(it) { text -> json.decodeFromString(response, text) } }
    }

    /** DELETE; the 204 body is ignored. [query] parameters (e.g. `?base_version=`) go on the URL. */
    suspend fun delete(path: List<String>, query: List<Pair<String, String>> = emptyList()): ApiResult<Unit> =
        call(HttpMethod.DELETE, path, null, query = query) { readJson(it) { } }

    /**
     * GET raw bytes into [target] (written to a sibling `.part` file, then renamed, so [target] is
     * either complete or absent). Error responses are read and classified like the JSON calls.
     */
    suspend fun download(path: List<String>, target: File): ApiResult<File> =
        call(HttpMethod.GET, path, null, accept = "*/*") { readFile(it, target) }

    private suspend fun <T> call(
        method: HttpMethod,
        path: List<String>,
        body: RequestBody?,
        accept: String = "application/json",
        query: List<Pair<String, String>> = emptyList(),
        read: (Response) -> ApiResult<T>,
    ): ApiResult<T> {
        val request = buildRequest(method, path, query, body, accept, tokenStore.token())
        val response = try {
            okHttpClient.newCall(request).await()
        } catch (e: IOException) {
            return report(ApiResult.Failure(ApiError.Network(e)))
        }
        val result = withContext(ioDispatcher) { response.use(read) }
        val error = result.errorOrNull()
        if (error is ApiError.Unauthorized && error.status == 401) tokenStore.clear()
        return report(result)
    }

    /** Tell [observer] unless the caller went away (a cancelled call says nothing about the server). */
    private suspend fun <T> report(result: ApiResult<T>): ApiResult<T> {
        if (currentCoroutineContext().isActive) observer.onCallOutcome(healthSignalOf(result))
        return result
    }

    private fun buildRequest(
        method: HttpMethod,
        path: List<String>,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        accept: String,
        token: String?,
    ): Request {
        val url = baseUrl.newBuilder().apply {
            path.forEach(::addPathSegment)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return Request.Builder()
            .url(url)
            .method(method.name, body)
            .header("Accept", accept)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
    }

    private fun readFile(response: Response, target: File): ApiResult<File> {
        if (!response.isSuccessful) return readJson(response) { target }
        val part = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            part.sink().buffer().use { sink -> sink.writeAll(response.body.source()) }
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
            ApiResult.Success(target)
        } catch (e: IOException) {
            part.delete()
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    private fun <T> readJson(response: Response, decode: (String) -> T): ApiResult<T> {
        val text = try {
            response.body.string()
        } catch (e: IOException) {
            return ApiResult.Failure(ApiError.Network(e))
        }
        if (!response.isSuccessful) return ApiResult.Failure(ApiError.fromHttp(response.code, text, response.header("Retry-After")))
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
            observer: ApiCallObserver = ApiCallObserver.None,
        ): ApiClient? = baseUrl.toHttpUrlOrNull()?.let { ApiClient(it, tokenStore, okHttpClient, ioDispatcher, observer = observer) }
    }
}

/** Enqueue the call and suspend until it answers; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
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
