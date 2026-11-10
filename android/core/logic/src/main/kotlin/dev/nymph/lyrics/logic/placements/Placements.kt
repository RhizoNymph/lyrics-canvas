package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferResponse

/** The canvases holding [snippet] besides the one it was loaded on, in the server's order. */
fun otherCanvases(snippet: Snippet): List<CanvasRef> = snippet.canvases.filter { it.id != snippet.canvasId }

/** The "⧉ N" badge's N: how many canvases hold [snippet], or null when it is only on this one. */
fun linkBadgeCount(snippet: Snippet): Int? = snippet.canvases.size.takeIf { it > 1 }

fun linkBadgeLabel(count: Int): String = "⧉ $count"

/** The editor's "Also on: A, B", or null for a snippet on this canvas only. */
fun alsoOnText(snippet: Snippet): String? = otherCanvases(snippet).takeIf { it.isNotEmpty() }?.joinToString(prefix = "Also on: ") { it.title }

/** The server's `canvases` order: lowercase title, then id. */
fun sortCanvasRefs(refs: Collection<CanvasRef>): List<CanvasRef> = refs.sortedWith(compareBy({ it.title.lowercase() }, { it.id.value }))

/**
 * Plain Delete on a selection: every snippet loses its placement on this canvas
 * (`DELETE /api/canvases/{cid}/snippets/{sid}`). [sharedIds] are on other canvases too and stay
 * there; [lastCopyIds] are deleted for good. Only those need the confirm the app always showed.
 * Built by [of], which refuses an empty selection.
 */
class RemovalPlan private constructor(val ids: List<SnippetId>, val sharedIds: Set<SnippetId>) {
    val lastCopyIds: List<SnippetId> = ids.filter { it !in sharedIds }

    /** Some snippet stays on another canvas: the action is "Remove from this canvas". */
    val removesShared: Boolean get() = sharedIds.isNotEmpty()

    /** Some snippet is deleted for good: confirm first. */
    val needsConfirm: Boolean get() = lastCopyIds.isNotEmpty()

    companion object {
        fun of(selected: Collection<Snippet>): RemovalPlan? {
            if (selected.isEmpty()) return null
            val distinct = selected.distinctBy { it.id }
            return RemovalPlan(distinct.map { it.id }, distinct.filter { otherCanvases(it).isNotEmpty() }.mapTo(LinkedHashSet()) { it.id })
        }
    }
}

/** Delete everywhere: the snippets and every canvas besides this one they disappear from. */
class DeleteEverywherePlan private constructor(val ids: List<SnippetId>, val otherCanvases: List<CanvasRef>) {
    companion object {
        fun of(selected: Collection<Snippet>): DeleteEverywherePlan? {
            if (selected.isEmpty()) return null
            val distinct = selected.distinctBy { it.id }
            return DeleteEverywherePlan(distinct.map { it.id }, sortCanvasRefs(distinct.flatMap(::otherCanvases).distinctBy { it.id }))
        }
    }
}

/** [refs] with [target] added (once), in the server's order. */
fun withCanvas(refs: List<CanvasRef>, target: CanvasRef): List<CanvasRef> =
    if (refs.any { it.id == target.id }) refs else sortCanvasRefs(refs + target)

/**
 * This canvas's snippets after a transfer to [target]: a move takes the transferred snippets off,
 * a show adds [target] to their `canvases` (badges).
 */
fun applyTransfer(snippets: List<Snippet>, response: TransferResponse, target: CanvasRef): List<Snippet> {
    val ids = (response.placed + response.alreadyOnTarget).toSet()
    return if (response.removedFromSource) {
        snippets.filter { it.id !in ids }
    } else {
        snippets.map { if (it.id in ids) it.copy(canvases = withCanvas(it.canvases, target)) else it }
    }
}

/** Snippets with canvas [id]'s title in their `canvases` replaced by [title] (re-sorted). */
fun withCanvasRenamed(snippets: List<Snippet>, id: CanvasId, title: String): List<Snippet> = snippets.map { s ->
    if (s.canvases.none { it.id == id }) s else s.copy(canvases = sortCanvasRefs(s.canvases.map { if (it.id == id) it.copy(title = title) else it }))
}

private fun snippets(n: Int) = if (n == 1) "1 snippet" else "$n snippets"

/** "Moved 3 snippets to Title" / "Showing 3 snippets on Title (1 was already there)". */
fun transferMessage(mode: TransferMode, response: TransferResponse, targetTitle: String): String {
    val total = response.placed.size + response.alreadyOnTarget.size
    val head = when (mode) {
        TransferMode.MOVE -> "Moved ${snippets(total)} to $targetTitle"
        TransferMode.SHOW -> "Showing ${snippets(total)} on $targetTitle"
    }
    val already = response.alreadyOnTarget.size
    return when (already) {
        0 -> head
        1 -> "$head (1 was already there)"
        else -> "$head ($already were already there)"
    }
}
