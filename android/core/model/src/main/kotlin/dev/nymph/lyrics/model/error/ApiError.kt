package dev.nymph.lyrics.model.error

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Every way an API call can fail. Produced by `:core:data`'s ApiClient and mapped by
 * `:core:logic` into feature errors (RhymeError, MergeError); never thrown.
 */
sealed interface ApiError {
    /** Human-readable summary, from the server's `detail` when there is one. */
    val message: String

    /**
     * The machine-readable code of a structured error body (`{"detail": {"error": code, "message": ...}}`),
     * or null when the server sent none (plain-string or pydantic details, no response at all).
     */
    val code: String? get() = null

    /** 401 (bad or expired token, bad credentials) or 403 (no credentials). */
    data class Unauthorized(val status: Int, val detail: String?) : ApiError {
        override val message: String get() = detail ?: "Not authorized"
    }

    /** 404: missing or not owned by the caller. */
    data class NotFound(val detail: String?, override val code: String? = null) : ApiError {
        override val message: String get() = detail ?: "Not found"
    }

    /**
     * 400 or 422: the server rejected the request; pydantic messages are joined with "; ".
     * [expressionError] is the located error of an `invalid_tag_expression` rejection; [index] is
     * the structured body's `index` (the first bad word of an `invalid_word` batch).
     */
    data class Validation(
        val status: Int,
        val detail: String,
        override val code: String? = null,
        val expressionError: ExpressionErrorBody? = null,
        val index: Int? = null,
    ) : ApiError {
        override val message: String get() = detail
    }

    /**
     * 5xx or any other non-2xx status. [retryAfterSeconds] is the `Retry-After` header when it is a
     * whole number of seconds (e.g. `related_words_warming`).
     */
    data class Server(
        val status: Int,
        val detail: String?,
        override val code: String? = null,
        val retryAfterSeconds: Int? = null,
        /** `detail.current` of a 409 (`version_conflict`): the server's current copy, decoded by the caller. */
        val current: JsonElement? = null,
    ) : ApiError {
        override val message: String get() = detail ?: "Server error ($status)"
    }

    /** No response: offline, DNS, timeout, connection reset, cancelled call. */
    data class Network(val cause: Throwable) : ApiError {
        override val message: String get() = cause.message ?: "Network error"
    }

    /**
     * Not sent: the server is unreachable (the health monitor says it is down) and the feature
     * needs it (rhymes, voice, presets saves...). Offline-capable edits never fail with this.
     */
    data object NeedsConnection : ApiError {
        override val message: String get() = "Needs connection"
    }

    /** A 2xx response whose body did not match the expected shape. */
    data class Decoding(val cause: Throwable) : ApiError {
        override val message: String get() = cause.message ?: "Unexpected response"
    }

    companion object {
        /**
         * Classify a non-2xx response by status, reading FastAPI's `detail` from the body and the
         * [retryAfter] header (seconds) for a 5xx.
         */
        fun fromHttp(status: Int, body: String?, retryAfter: String? = null): ApiError {
            val detail = detailFromBody(body)
            val code = errorCodeFromBody(body)
            return when (status) {
                401, 403 -> Unauthorized(status, detail)
                404 -> NotFound(detail, code)
                400, 422 -> Validation(status, detail ?: "Invalid request ($status)", code, expressionErrorFromBody(body), indexFromBody(body))
                else -> Server(status, detail, code, retryAfterSeconds(retryAfter), currentFromBody(body))
            }
        }

        /** `Retry-After` as delta seconds (>= 0); an HTTP date or garbage is null. */
        fun retryAfterSeconds(header: String?): Int? = header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

        /** `detail.current` of a structured body (a 409 `version_conflict`), else null. */
        fun currentFromBody(body: String?): JsonElement? = (detailElement(body) as? JsonObject)?.get("current")

        /** The integer `detail.index` of a structured body, else null. */
        fun indexFromBody(body: String?): Int? =
            ((detailElement(body) as? JsonObject)?.get("index") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        /**
         * FastAPI error bodies: `{"detail": "text"}` from HTTPException,
         * `{"detail": [{"msg": "...", ...}, ...]}` from request validation (messages joined by "; "), or
         * a structured `{"detail": {"error": "<code>", "message": "text"}}` (its message).
         */
        fun detailFromBody(body: String?): String? {
            val detail = detailElement(body) ?: return null
            return when (detail) {
                is JsonPrimitive -> if (detail.isString) detail.content else null
                is JsonArray -> detail
                    .mapNotNull { item -> ((item as? JsonObject)?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("; ")
                is JsonObject -> detail.stringField("message")
            }
        }

        /** The `error` code of a structured `{"detail": {"error": "<code>", ...}}` body, else null. */
        fun errorCodeFromBody(body: String?): String? = (detailElement(body) as? JsonObject)?.stringField("error")

        /** `detail.expression_error` (`{code, position, message}`) of an expression rejection, else null. */
        fun expressionErrorFromBody(body: String?): ExpressionErrorBody? {
            val raw = (detailElement(body) as? JsonObject)?.get("expression_error") as? JsonObject ?: return null
            val code = raw.stringField("code") ?: return null
            val position = (raw["position"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            return ExpressionErrorBody(code, position, raw.stringField("message") ?: "")
        }

        private fun detailElement(body: String?): JsonElement? {
            if (body.isNullOrBlank()) return null
            val root = try {
                LyricsJson.parseToJsonElement(body)
            } catch (e: SerializationException) {
                return null
            }
            return (root as? JsonObject)?.get("detail")
        }

        private fun JsonObject.stringField(name: String): String? =
            (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/** The server's located expression error; [position] is a code point offset into the sent expression. */
data class ExpressionErrorBody(val code: String, val position: Int, val message: String)
