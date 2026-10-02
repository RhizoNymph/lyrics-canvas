package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Merge direction: vertical stacks lines (y then x order), horizontal joins them side by side. */
@Serializable
enum class MergeMode {
    @SerialName("vertical") VERTICAL,
    @SerialName("horizontal") HORIZONTAL,
}

/**
 * Body of `POST /api/canvases/{id}/merge`. Built only through [of], which de-duplicates the ids
 * and refuses fewer than two distinct ones (the server answers 422 for that), so a request that
 * exists is one the server can accept.
 */
@Serializable
@ConsistentCopyVisibility
data class MergeRequest private constructor(
    @SerialName("snippet_ids") val snippetIds: List<SnippetId>,
    val mode: MergeMode,
) {
    companion object {
        /** De-duplicated ids in first-seen order, or null when fewer than two distinct ids remain. */
        fun of(snippetIds: Collection<SnippetId>, mode: MergeMode): MergeRequest? {
            val distinct = snippetIds.distinct()
            return if (distinct.size < 2) null else MergeRequest(distinct, mode)
        }
    }
}

/** Response of the merge endpoint: the new snippet and the ids it replaced. */
@Serializable
data class MergeResponse(
    val snippet: Snippet,
    @SerialName("deleted_ids") val deletedIds: List<SnippetId>,
)
