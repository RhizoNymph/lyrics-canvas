package dev.nymph.lyrics.app.canvas.placements

import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.placements.CANVAS_TITLE_MAX
import dev.nymph.lyrics.logic.placements.CanvasTitleProblem
import dev.nymph.lyrics.logic.placements.DeleteEverywherePlan
import dev.nymph.lyrics.logic.placements.PlacementError
import dev.nymph.lyrics.logic.placements.RemovalPlan
import dev.nymph.lyrics.model.PlacementErrorCode

/** The text shown for a failed rename, transfer, removal or delete, by server code where there is one. */
fun PlacementError.userMessage(): String = when (this) {
    is PlacementError.Rejected -> when (code) {
        PlacementErrorCode.INVALID_CANVAS_TITLE -> "The server rejected this title. Use 1–$CANVAS_TITLE_MAX characters."
        PlacementErrorCode.PLACEMENT_NOT_FOUND -> "A snippet is no longer on this canvas. Reload to see the latest."
        PlacementErrorCode.CANVAS_NOT_FOUND -> "That canvas no longer exists."
        PlacementErrorCode.SAME_CANVAS -> "Pick a different canvas: the snippets are already here."
    }
    PlacementError.SessionExpired -> SESSION_EXPIRED
    is PlacementError.Http -> message
    is PlacementError.Network -> "Can't reach the server. Check your connection."
    is PlacementError.Unexpected -> "Unexpected response from the server."
}

fun CanvasTitleProblem.userMessage(): String = when (this) {
    CanvasTitleProblem.EMPTY -> "Enter a title."
    CanvasTitleProblem.TOO_LONG -> "Titles can be at most $CANVAS_TITLE_MAX characters."
}

/** The plain delete action's label: "Remove from this canvas" when a selected snippet is on other canvases. */
fun RemovalPlan.actionLabel(): String = if (removesShared) REMOVE_FROM_CANVAS else "Delete"

const val REMOVE_FROM_CANVAS: String = "Remove from this canvas"

/** Confirm dialog title for a removal that deletes some snippets for good ([RemovalPlan.needsConfirm]). */
fun RemovalPlan.confirmTitle(): String = when {
    !removesShared -> if (ids.size == 1) "Delete snippet?" else "Delete ${ids.size} snippets?"
    else -> "Remove ${ids.size} snippets from this canvas?"
}

fun RemovalPlan.confirmText(): String = if (!removesShared) {
    "This cannot be undone."
} else {
    val n = lastCopyIds.size
    (if (n == 1) "1 of them is" else "$n of them are") + " on no other canvas and will be deleted. This cannot be undone."
}

fun DeleteEverywherePlan.confirmTitle(): String = if (ids.size == 1) "Delete snippet everywhere?" else "Delete ${ids.size} snippets everywhere?"

/** Names the other canvases the snippets disappear from. */
fun DeleteEverywherePlan.confirmText(): String {
    val where = if (otherCanvases.isEmpty()) {
        "Not on any other canvas."
    } else {
        "Also removed from: " + otherCanvases.joinToString { it.title } + "."
    }
    return "$where Recordings are deleted too. This cannot be undone."
}
