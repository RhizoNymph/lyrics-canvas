package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A canvas whose title matches every term (`GET /api/search` `canvases[]`), `updated_at` desc.
 * Titles match by the same NFC + lowercase substring rule without stripping rest marks.
 */
@Serializable
data class CanvasSearchHit(
    val id: CanvasId,
    val title: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * One of the caller's snippets whose display text matches (`snippets[]`), `content_updated_at`
 * desc. [excerpt] is the first display line containing the first term (at most about 160 code
 * points, cut 40 characters before the match with a leading and/or trailing `…`; `""` when the
 * snippet has no non-empty display line); clients find highlight positions themselves.
 * Datetimes are the server's `str(datetime)` strings. [canvases] lists every canvas it is placed
 * on, sorted by lowercase title then id.
 */
@Serializable
data class SnippetSearchHit(
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("snippet_type") val snippetType: SnippetType,
    @SerialName("content_updated_at") val contentUpdatedAt: String,
    val excerpt: String,
    val canvases: List<CanvasRef> = emptyList(),
)

/** Response of `GET /api/search?q=&limit=`; [limit][SearchLimits] applies to each list separately. */
@Serializable
data class SearchResponse(
    val canvases: List<CanvasSearchHit> = emptyList(),
    val snippets: List<SnippetSearchHit> = emptyList(),
)

object SearchLimits {
    const val DEFAULT: Int = 50
    const val MAX: Int = 200
}

/** The `error` codes of `GET /api/search`; unknown codes parse to null. */
enum class SearchErrorCode(val wire: String) {
    /** 422: `q` is empty or over 200 characters after a trim. */
    INVALID_SEARCH_QUERY("invalid_search_query"),
    ;

    companion object {
        fun parse(code: String?): SearchErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
