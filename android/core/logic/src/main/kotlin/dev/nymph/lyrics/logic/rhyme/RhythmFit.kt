package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.RhythmUnavailableReason
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlin.math.roundToInt

/**
 * "Fit rhythm" on the rhyme sheet (round-4 contract section 6): the server ranks candidates by how
 * their stress fits the placed rhythm of the snippet anchor's slot syllables. The client only asks
 * (`rhythm_fit`), shows the outcome and each row's percent.
 */

/** The toggle's label. */
const val RHYTHM_FIT_LABEL: String = "Fit rhythm"

/** The toggle applies only with a snippet anchor (a typed phrase has no score). */
fun rhythmFitEligible(selectedIds: Set<SnippetId>, snippets: Map<SnippetId, Snippet>, excluded: Set<SnippetId>): Boolean =
    anchorSnippets(selectedIds, snippets, excluded).isNotEmpty()

/** What became of rhythm fit for the shown rows. */
sealed interface RhythmOutcome {
    /** Off, or no snippet anchor (nothing was asked). */
    data object NotAsked : RhythmOutcome

    /** Applied: rows are banded, then ordered by `rhythm_fit`. */
    data object Applied : RhythmOutcome

    /** Asked for, not applied, because of [reason]. */
    data class Unavailable(val reason: RhythmUnavailableReason) : RhythmOutcome
}

/**
 * The outcome of a response to a query that [asked] for rhythm fit or not. A server that ignores
 * the field (asked, not applied, no reason) reads as [RhythmUnavailableReason.OTHER].
 */
fun rhythmOutcome(asked: Boolean, applied: Boolean, reason: RhythmUnavailableReason?): RhythmOutcome = when {
    !asked -> RhythmOutcome.NotAsked
    applied -> RhythmOutcome.Applied
    else -> RhythmOutcome.Unavailable(reason ?: RhythmUnavailableReason.OTHER)
}

/** The note under the toggle when rhythm fit was asked for and not applied; null otherwise. */
fun rhythmNote(outcome: RhythmOutcome): String? = when (outcome) {
    RhythmOutcome.NotAsked, RhythmOutcome.Applied -> null
    is RhythmOutcome.Unavailable -> when (outcome.reason) {
        RhythmUnavailableReason.NO_SCORE -> "Fit rhythm unavailable: this snippet has no score"
        RhythmUnavailableReason.SLOT_UNPLACED -> "Fit rhythm unavailable: place every slot syllable on the staff"
        RhythmUnavailableReason.OTHER -> "Fit rhythm unavailable on this server"
    }
}

/** The rhythm fit as a whole percent (0..100) for a row's label, or null without one. */
fun rhythmPercent(fit: Double?): Int? =
    fit?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt().coerceIn(0, 100) }
