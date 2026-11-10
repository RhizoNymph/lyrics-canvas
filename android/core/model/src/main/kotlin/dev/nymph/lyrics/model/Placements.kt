package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A canvas a snippet is placed on (`SnippetResponse.canvases[]`). */
@Serializable
data class CanvasRef(
    val id: CanvasId,
    val title: String,
)

/** What a transfer does to the source canvas: [MOVE] removes the snippets there, [SHOW] keeps them. */
@Serializable
enum class TransferMode {
    @SerialName("move") MOVE,
    @SerialName("show") SHOW,
}

/**
 * Body of `POST /api/canvases/{cid}/snippets/transfer`. Built only through [of], which
 * de-duplicates the ids and refuses an empty or oversized batch (the server's 1..500), so a request
 * that exists has an acceptable size.
 */
@Serializable
@ConsistentCopyVisibility
data class TransferRequest private constructor(
    @SerialName("snippet_ids") val snippetIds: List<SnippetId>,
    @SerialName("target_canvas_id") val targetCanvasId: CanvasId,
    val mode: TransferMode,
) {
    companion object {
        const val MAX_SNIPPETS: Int = 500

        /** De-duplicated ids in first-seen order, or null when there are none or more than [MAX_SNIPPETS]. */
        fun of(snippetIds: Collection<SnippetId>, targetCanvasId: CanvasId, mode: TransferMode): TransferRequest? {
            val distinct = snippetIds.distinct()
            return if (distinct.isEmpty() || distinct.size > MAX_SNIPPETS) null else TransferRequest(distinct, targetCanvasId, mode)
        }
    }
}

/**
 * Response of the transfer: snippets newly [placed] on the target, those [alreadyOnTarget] (their
 * target placement untouched), and whether the source placements were removed (move mode).
 */
@Serializable
data class TransferResponse(
    @SerialName("target_canvas_id") val targetCanvasId: CanvasId,
    val placed: List<SnippetId>,
    @SerialName("already_on_target") val alreadyOnTarget: List<SnippetId>,
    @SerialName("removed_from_source") val removedFromSource: Boolean,
)

/** Response of `DELETE /api/canvases/{cid}/snippets/{sid}`: true when that was the snippet's last canvas. */
@Serializable
data class RemovePlacementResponse(
    @SerialName("snippet_deleted") val snippetDeleted: Boolean,
)

/** The structured error codes of canvas rename and snippet placements (`detail.error`). */
enum class PlacementErrorCode(val wire: String) {
    /** 422: the title is empty after a strip, or longer than 255 code points. */
    INVALID_CANVAS_TITLE("invalid_canvas_title"),

    /** 404: the snippet is not placed on that canvas. */
    PLACEMENT_NOT_FOUND("placement_not_found"),

    /** 404: the canvas is missing or not the caller's. */
    CANVAS_NOT_FOUND("canvas_not_found"),

    /** 422: a transfer whose target is its source. */
    SAME_CANVAS("same_canvas"),
    ;

    companion object {
        fun parse(code: String?): PlacementErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
