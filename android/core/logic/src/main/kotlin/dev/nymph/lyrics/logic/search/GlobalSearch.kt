package dev.nymph.lyrics.logic.search

import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.model.SearchErrorCode
import dev.nymph.lyrics.model.error.ApiError

/** `GET /api/search`: the longest `q` the server accepts, in code points after a trim. */
const val MAX_SEARCH_QUERY_LENGTH: Int = 200

/** What the canvas list does with the search field's text. */
sealed interface GlobalSearchInput {
    /** Nothing to search: the normal canvas list shows. */
    data object Blank : GlobalSearchInput

    /** Over [MAX_SEARCH_QUERY_LENGTH]; no request is sent. */
    data class TooLong(val length: Int) : GlobalSearchInput

    /** The trimmed text to send as `q`. */
    @ConsistentCopyVisibility
    data class Valid internal constructor(val query: String) : GlobalSearchInput
}

fun checkGlobalSearch(raw: String): GlobalSearchInput {
    val trimmed = jsTrim(raw)
    if (trimmed.isEmpty()) return GlobalSearchInput.Blank
    val length = trimmed.codePointCount(0, trimmed.length)
    return if (length > MAX_SEARCH_QUERY_LENGTH) GlobalSearchInput.TooLong(length) else GlobalSearchInput.Valid(trimmed)
}

/** A highlighted span of an excerpt, `[start, end)` in UTF-16 indices. */
data class TextSpan(val start: Int, val end: Int)

/**
 * Where the terms of [query] occur in [text] (an excerpt: display text, no rest marks), ignoring
 * case; overlapping and touching occurrences are merged, in order.
 */
fun highlightSpans(text: String, query: SearchQuery): List<TextSpan> {
    val found = mutableListOf<TextSpan>()
    for (term in query.terms) {
        var from = 0
        while (from <= text.length - term.length) {
            val at = text.indexOf(term, from, ignoreCase = true)
            if (at < 0) break
            found += TextSpan(at, at + term.length)
            from = at + 1
        }
    }
    found.sortWith(compareBy(TextSpan::start, TextSpan::end))
    val merged = mutableListOf<TextSpan>()
    for (span in found) {
        val last = merged.lastOrNull()
        if (last != null && span.start <= last.end) {
            merged[merged.lastIndex] = TextSpan(last.start, maxOf(last.end, span.end))
        } else {
            merged += span
        }
    }
    return merged
}

/** Why a search across canvases failed; UI state, never thrown. */
sealed interface SearchError {
    /** 422 `invalid_search_query` (or caught locally: [TooLong][GlobalSearchInput.TooLong]). */
    data class InvalidQuery(val serverMessage: String?) : SearchError

    /** 401: the app is signing out. */
    data object SessionExpired : SearchError

    /** Any other HTTP failure. */
    data class Http(val status: Int, val message: String) : SearchError

    /** No response. */
    data class Network(val message: String) : SearchError

    /** A 2xx response the app could not read. */
    data class Unexpected(val message: String) : SearchError
}

fun toSearchError(error: ApiError): SearchError {
    if (SearchErrorCode.parse(error.code) == SearchErrorCode.INVALID_SEARCH_QUERY) return SearchError.InvalidQuery(error.message)
    return when (error) {
        is ApiError.Network -> SearchError.Network(error.message)
        is ApiError.Decoding -> SearchError.Unexpected(error.message)
        is ApiError.Unauthorized -> if (error.status == 401) SearchError.SessionExpired else SearchError.Http(error.status, error.message)
        is ApiError.NotFound -> SearchError.Http(404, error.message)
        is ApiError.Validation -> SearchError.Http(error.status, error.message)
        is ApiError.Server -> SearchError.Http(error.status, error.message)
    }
}
