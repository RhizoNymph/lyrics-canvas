package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder
import kotlin.math.roundToInt

/**
 * The fixed band edges by candidate score (the one Android copy; the server computes `band`):
 * perfect >= 0.95 > strong >= 0.85 > near >= 0.75 > loose.
 */
const val PERFECT_MIN_SCORE: Double = 0.95
const val STRONG_MIN_SCORE: Double = 0.85
const val NEAR_MIN_SCORE: Double = 0.75

/** The band of [score]: only for a payload without `band` (the server always sends one). */
fun bandOf(score: Double): RhymeBand = when {
    score >= PERFECT_MIN_SCORE -> RhymeBand.PERFECT
    score >= STRONG_MIN_SCORE -> RhymeBand.STRONG
    score >= NEAR_MIN_SCORE -> RhymeBand.NEAR
    else -> RhymeBand.LOOSE
}

/** A row's band: the server's, else from its score. */
fun rowBand(band: RhymeBand?, score: Double): RhymeBand = band ?: bandOf(score)

/** The section header text. */
val RhymeBand.label: String
    get() = when (this) {
        RhymeBand.PERFECT -> "Perfect"
        RhymeBand.STRONG -> "Strong"
        RhymeBand.NEAR -> "Near"
        RhymeBand.LOOSE -> "Loose"
    }

/** The toggle's labels. */
val RhymeOrder.label: String
    get() = when (this) {
        RhymeOrder.RHYME -> "Rhyme"
        RhymeOrder.MEANING -> "Rhyme, then meaning"
    }

/** Shown when meaning order was asked for and the server answered in rhyme order. */
const val MEANING_UNAVAILABLE_NOTE: String = "Meaning order unavailable"

/** How a list shows its rows, from the order it asked for and the order the server applied. */
enum class OrderPresentation {
    /** Rhyme order: rows as they come. */
    PLAIN,

    /** Meaning order applied: band headers where the band changes. */
    BANDED,

    /** Meaning asked, rhyme applied: no headers, and [MEANING_UNAVAILABLE_NOTE]. */
    MEANING_UNAVAILABLE,
}

fun orderPresentation(asked: RhymeOrder, applied: RhymeOrder): OrderPresentation = when {
    applied == RhymeOrder.MEANING -> OrderPresentation.BANDED
    asked == RhymeOrder.MEANING -> OrderPresentation.MEANING_UNAVAILABLE
    else -> OrderPresentation.PLAIN
}

/**
 * The order a list's current rows were fetched with: what it asked for and what the server
 * applied (from the first page; later pages of the same query reuse it), and what became of
 * "Fit rhythm" (the rhyme sheet only). Rhythm fit applied bands the rows like meaning order.
 */
data class ResultOrdering(
    val asked: RhymeOrder = RhymeOrder.RHYME,
    val applied: RhymeOrder = RhymeOrder.RHYME,
    val rhythm: RhythmOutcome = RhythmOutcome.NotAsked,
) {
    val presentation: OrderPresentation
        get() = if (rhythm == RhythmOutcome.Applied) OrderPresentation.BANDED else orderPresentation(asked, applied)

    /** Meaning was asked for and the server answered in rhyme order (the note shows even when banded by rhythm). */
    val meaningUnavailable: Boolean get() = asked == RhymeOrder.MEANING && applied != RhymeOrder.MEANING
}

/** A list entry: a band header or a row. */
sealed interface BandedItem<out T> {
    data class Header(val band: RhymeBand) : BandedItem<Nothing>

    data class Row<out T>(val value: T, val band: RhymeBand) : BandedItem<T>
}

/**
 * [rows] as list entries: with [OrderPresentation.BANDED], a header before the first row and
 * wherever the band changes (an appended page that continues a band adds no header); otherwise
 * the rows alone.
 */
fun <T> withBandHeaders(rows: List<T>, presentation: OrderPresentation, band: (T) -> RhymeBand): List<BandedItem<T>> {
    val out = ArrayList<BandedItem<T>>(rows.size + 4)
    var last: RhymeBand? = null
    for (row in rows) {
        val b = band(row)
        if (presentation == OrderPresentation.BANDED && b != last) out += BandedItem.Header(b)
        out += BandedItem.Row(row, b)
        last = b
    }
    return out
}

/** The similarity as a whole percent (0..100) for the quiet "≈NN%" label, or null without one. */
fun similarityPercent(similarity: Double?): Int? =
    similarity?.takeIf { it.isFinite() }?.let { (it * 100).roundToInt().coerceIn(0, 100) }
