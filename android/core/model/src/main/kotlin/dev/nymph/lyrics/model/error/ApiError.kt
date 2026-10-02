package dev.nymph.lyrics.model.error

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
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

    /** 401 (bad or expired token, bad credentials) or 403 (no credentials). */
    data class Unauthorized(val status: Int, val detail: String?) : ApiError {
        override val message: String get() = detail ?: "Not authorized"
    }

    /** 404: missing or not owned by the caller. */
    data class NotFound(val detail: String?) : ApiError {
        override val message: String get() = detail ?: "Not found"
    }

    /** 400 or 422: the server rejected the request; pydantic messages are joined with "; ". */
    data class Validation(val status: Int, val detail: String) : ApiError {
        override val message: String get() = detail
    }

    /** 5xx or any other non-2xx status. */
    data class Server(val status: Int, val detail: String?) : ApiError {
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
            return when (status) {
                401, 403 -> Unauthorized(status, detail)
                404 -> NotFound(detail)
                400, 422 -> Validation(status, detail ?: "Invalid request ($status)")
                else -> Server(status, detail)
            }
        }

        /**
         * FastAPI error bodies: `{"detail": "text"}` from HTTPException, or
         * `{"detail": [{"msg": "...", ...}, ...]}` from request validation (messages joined by "; ").
         */
        fun detailFromBody(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val root = try {
                LyricsJson.parseToJsonElement(body)
            } catch (e: SerializationException) {
                return null
            }
            val detail = (root as? JsonObject)?.get("detail") ?: return null
            return when (detail) {
                is JsonPrimitive -> if (detail.isString) detail.content else null
                is JsonArray -> detail
                    .mapNotNull { item -> ((item as? JsonObject)?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("; ")
                else -> null
            }
        }
    }
}
