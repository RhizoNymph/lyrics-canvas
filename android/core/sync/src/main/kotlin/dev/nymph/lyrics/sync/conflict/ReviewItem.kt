package dev.nymph.lyrics.sync.conflict

import dev.nymph.lyrics.logic.rests.displayText
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpId
import dev.nymph.lyrics.sync.outbox.OpState
import dev.nymph.lyrics.sync.outbox.SyncOp
import dev.nymph.lyrics.sync.store.LocalData
import java.util.Locale

/** One side of a review (mine or the server's), as text the screen shows. */
sealed interface ReviewSide {
    data class OfSnippet(val text: String, val type: SnippetType, val tags: List<String>) : ReviewSide

    data class OfCanvas(val title: String) : ReviewSide

    data class OfTag(val name: String, val color: String) : ReviewSide

    data class OfWord(val text: String, val tags: List<String>) : ReviewSide

    /** Mine deletes it (the local change was a delete). */
    data object Deleted : ReviewSide

    /** The server no longer has it. */
    data object Gone : ReviewSide
}

/** What kind of thing a change is to (for headings). */
enum class EntityKind { SNIPPET, CANVAS, TAG, WORD }

fun kindOf(entity: EntityRef): EntityKind = when (entity) {
    is EntityRef.Snippet -> EntityKind.SNIPPET
    is EntityRef.Canvas -> EntityKind.CANVAS
    is EntityRef.Tag -> EntityKind.TAG
    is EntityRef.Word -> EntityKind.WORD
}

/**
 * A parked change as the review shows it: mine next to the server's, and the choices. For a merge
 * [changedOriginals] are the first lines of the originals that changed (or [missingOriginals]).
 */
data class ReviewItem(
    val opId: OpId,
    val kind: EntityKind,
    val label: String,
    val reason: String,
    val mine: ReviewSide?,
    val server: ReviewSide?,
    val resolutions: List<Resolution>,
    val isMerge: Boolean = false,
    val changedOriginals: List<String> = emptyList(),
    val missingOriginals: Int = 0,
)

/** Every parked change in outbox order. */
fun reviewItems(d: LocalData): List<ReviewItem> = d.outbox.parked.mapNotNull { reviewItem(d, it.id) }

fun reviewItem(d: LocalData, opId: OpId): ReviewItem? {
    val entry = d.outbox[opId] ?: return null
    val conflict = (entry.state as? OpState.Parked)?.conflict ?: return null
    val tagNames = d.tags.associate { it.id to it.name }
    fun names(ids: Collection<dev.nymph.lyrics.model.TagId>) = ids.mapNotNull(tagNames::get).sortedBy { it.lowercase(Locale.ROOT) }
    val isDelete = entry.op is SyncOp.DeleteSnippet || entry.op is SyncOp.RemoveFromCanvas || entry.op is SyncOp.DeleteCanvas ||
        entry.op is SyncOp.DeleteTag || entry.op is SyncOp.DeleteWord
    val mine: ReviewSide? = if (isDelete) {
        ReviewSide.Deleted
    } else {
        when (val e = conflict.entity) {
            is EntityRef.Snippet -> d.snippet(e.id)?.let { ReviewSide.OfSnippet(displayText(it.rawText), it.snippetType, names(it.tagIds)) }
            is EntityRef.Canvas -> d.canvas(e.id)?.let { ReviewSide.OfCanvas(it.title) }
            is EntityRef.Tag -> d.tag(e.id)?.let { ReviewSide.OfTag(it.name, it.color) }
            is EntityRef.Word -> d.word(e.id)?.let { ReviewSide.OfWord(it.text, names(it.tagIds)) }
        }
    }
    val server: ReviewSide? = when (conflict) {
        is Conflict.Changed -> when (val c = conflict.server) {
            is ServerCopy.OfSnippet -> ReviewSide.OfSnippet(displayText(c.snippet.rawText), c.snippet.snippetType, c.snippet.tags.map { it.name }.sortedBy { it.lowercase(Locale.ROOT) })
            is ServerCopy.OfCanvas -> ReviewSide.OfCanvas(c.canvas.title)
            is ServerCopy.OfTag -> ReviewSide.OfTag(c.tag.name, c.tag.color)
            is ServerCopy.OfWord -> ReviewSide.OfWord(c.word.text, c.word.tags.map { it.name }.sortedBy { it.lowercase(Locale.ROOT) })
        }
        is Conflict.DeletedOnServer -> ReviewSide.Gone
        is Conflict.MergeChanged, is Conflict.Rejected -> null
    }
    val merge = conflict as? Conflict.MergeChanged
    return ReviewItem(
        opId = entry.id,
        kind = kindOf(conflict.entity),
        label = labelOf(d, conflict.entity),
        reason = reason(conflict),
        mine = mine,
        server = server,
        resolutions = resolutionsFor(conflict),
        isMerge = merge != null,
        changedOriginals = merge?.changed?.map { s -> displayText(s.rawText).lineSequence().firstOrNull { it.isNotBlank() } ?: "(empty)" }.orEmpty(),
        missingOriginals = merge?.missing?.size ?: 0,
    )
}

/** A line-by-line comparison for the review's text: which lines of each side the other lacks. */
data class LineDiff(val mineOnly: Set<Int>, val serverOnly: Set<Int>)

/** Lines (by index) of [mine] not in [server] and the other way round; a simple multiset match, not an LCS. */
fun lineDiff(mine: String, server: String): LineDiff {
    val a = mine.split("\n")
    val b = server.split("\n")
    fun only(x: List<String>, y: List<String>): Set<Int> {
        val left = y.groupingBy { it }.eachCount().toMutableMap()
        return x.indices.filterTo(HashSet()) { i ->
            val n = left[x[i]] ?: 0
            if (n > 0) {
                left[x[i]] = n - 1
                false
            } else {
                true
            }
        }
    }
    return LineDiff(only(a, b), only(b, a))
}
