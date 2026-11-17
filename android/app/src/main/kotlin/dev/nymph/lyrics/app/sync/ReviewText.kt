package dev.nymph.lyrics.app.sync

import dev.nymph.lyrics.logic.score.barCount
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.sync.conflict.EntityKind
import dev.nymph.lyrics.sync.conflict.ResolveError
import dev.nymph.lyrics.sync.conflict.Resolution
import dev.nymph.lyrics.sync.conflict.ReviewItem
import dev.nymph.lyrics.sync.conflict.ReviewSide

/** The review's button for [resolution] on [item]. */
fun resolutionLabel(item: ReviewItem, resolution: Resolution): String = when (resolution) {
    Resolution.KEEP_SERVERS -> when {
        item.isMerge -> "Keep server's (drop the merge)"
        item.server == null && item.mine != null -> "Discard mine"
        item.server == ReviewSide.Gone -> "Keep server's (accept the deletion)"
        else -> "Keep server's"
    }
    Resolution.OVERWRITE_WITH_MINE -> "Overwrite with mine"
    Resolution.SAVE_MINE_AS_NEW -> "Save mine as new"
    Resolution.RETRY_MERGE -> "Retry merge"
}

fun kindLabel(kind: EntityKind): String = when (kind) {
    EntityKind.SNIPPET -> "Snippet"
    EntityKind.CANVAS -> "Canvas title"
    EntityKind.TAG -> "Tag"
    EntityKind.WORD -> "Word"
}

/** One side as lines of text for the review (the snippet text itself is shown separately). */
fun sideSummary(side: ReviewSide?): List<String> = when (side) {
    null -> emptyList()
    is ReviewSide.OfSnippet -> listOfNotNull(
        "Type: ${side.type.name.lowercase()}",
        side.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Tags: "),
        scoreSummary(side.score),
    )
    is ReviewSide.OfCanvas -> listOf(side.title)
    is ReviewSide.OfTag -> listOf(side.name, "Colour ${side.color}")
    is ReviewSide.OfWord -> listOfNotNull(side.text, side.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Tags: "))
    ReviewSide.Deleted -> listOf("Deleted")
    ReviewSide.Gone -> listOf("Deleted on the server")
}

/** The review's note when the two snippet sides have different scores. */
const val CADENCE_CHANGED: String = "Cadence changed"

/** A score in one line for the review: meter, tempo, bars and placed syllables; null without a score. */
fun scoreSummary(score: Score?): String? {
    score ?: return null
    val meter = score.meters.first()
    val bpm = score.tempos.first().bpm.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    val notes = score.lines.sumOf { it.notes.size }
    val changes = (score.meters.size - 1) + (score.tempos.size - 1)
    return "Score: ${meter.num}/${meter.den}, $bpm bpm, ${barCount(score)} bar(s), $notes placed" +
        (if (changes > 0) ", $changes change(s)" else "")
}

fun resolveErrorMessage(error: ResolveError): String = when (error) {
    ResolveError.NOT_PARKED -> "This change is no longer waiting for review."
    ResolveError.NOT_OFFERED -> "That choice is not available for this change."
    ResolveError.NOTHING_TO_SAVE -> "There is no local copy left to save."
}
