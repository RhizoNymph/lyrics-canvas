package dev.nymph.lyrics.sync.conflict

import dev.nymph.lyrics.logic.rests.displayText
import dev.nymph.lyrics.sync.outbox.EntityRef
import dev.nymph.lyrics.sync.outbox.OpId
import dev.nymph.lyrics.sync.store.LocalData

/** The longest label a conflict alert quotes. */
const val ALERT_LABEL_MAX: Int = 40

/** Raised once, when a change is parked: the snackbar and its Review action. */
data class ConflictAlert(val opId: OpId, val conflict: Conflict, val label: String) {
    /** `Change to "<label>" isn't synced: <why>`. */
    val message: String get() = "Change to \"$label\" isn't synced: ${reason(conflict)}"
}

fun reason(conflict: Conflict): String = when (conflict) {
    is Conflict.Changed -> "it was also changed on the server"
    is Conflict.DeletedOnServer -> "it was deleted on the server"
    is Conflict.MergeChanged -> if (conflict.missing.isEmpty()) "an original was also changed on the server" else "an original is gone from the server"
    is Conflict.Rejected -> "the server refused it (${conflict.message})"
}

/** A short name for [entity] from the local copy: a snippet's first display line, a title, a tag or word. */
fun labelOf(d: LocalData, entity: EntityRef): String {
    val text = when (entity) {
        is EntityRef.Snippet -> d.snippet(entity.id)?.let { s -> displayText(s.rawText).lineSequence().firstOrNull { it.isNotBlank() } } ?: "a snippet"
        is EntityRef.Canvas -> d.canvas(entity.id)?.title ?: "a canvas"
        is EntityRef.Tag -> d.tag(entity.id)?.name ?: "a tag"
        is EntityRef.Word -> d.word(entity.id)?.text ?: "a word"
    }
    val cps = text.codePoints().toArray()
    return if (cps.size <= ALERT_LABEL_MAX) text else String(cps, 0, ALERT_LABEL_MAX - 1) + "…"
}
