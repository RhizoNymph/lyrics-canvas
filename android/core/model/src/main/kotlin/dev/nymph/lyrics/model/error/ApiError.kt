package dev.nymph.lyrics.model.error

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

    /** 400 or 422: the server rejected the request; pydantic messages are joined with "; ". */
    data class Validation(val status: Int, val detail: String, override val code: String? = null) : ApiError {
        override val message: String get() = detail
    }

    /** 5xx or any other non-2xx status. */
    data class Server(val status: Int, val detail: String?, override val code: String? = null) : ApiError {
        override val message: String get() = detail ?: "Server error ($status)"
    }

    /** No response: offline, DNS, timeout, connection reset, cancelled call. */
    data class Network(val cause: Throwable) : ApiError {
        override val message: String get() = cause.message ?: "Network error"
    }

    /** A 2xx response whose body did not match the expected shape. */
    data class Decoding(val cause: Throwable) : ApiError {
        override val message: String get() = cause.message ?: "Unexpected response"
    }

    companion object {
        /** Classify a non-2xx response by status, reading FastAPI's `detail` from the body. */
        fun fromHttp(status: Int, body: String?): ApiError {
            val detail = detailFromBody(body)
            val code = errorCodeFromBody(body)
            return when (status) {
                401, 403 -> Unauthorized(status, detail)
                404 -> NotFound(detail, code)
                400, 422 -> Validation(status, detail ?: "Invalid request ($status)", code)
                else -> Server(status, detail, code)
            }
        }

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
