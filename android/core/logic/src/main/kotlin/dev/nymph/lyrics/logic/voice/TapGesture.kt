package dev.nymph.lyrics.logic.voice

/** A second tap this soon after the first one's down is a double tap (stop). */
const val DOUBLE_TAP_WINDOW_MS: Long = 300

/**
 * The tap-to-break gesture of one take. Times are ms since the recorder started.
 *
 * A down records its time as [pending] at once. A second down less than the window later is a
 * double tap: the take stops and the pending break is dropped. Otherwise, once the window has
 * passed ([tick], or the next down), the pending time becomes a break. [breaks] is ascending.
 * Only built from [Initial] through [down] / [tick].
 */
@ConsistentCopyVisibility
data class TapState private constructor(
    val breaks: List<Long>,
    val pending: Long?,
) {
    /** The line the next words land on: one more than the confirmed breaks. */
    val lineNumber: Int get() = breaks.size + 1

    /** A pointer went down at [atMs]. */
    fun down(atMs: Long, windowMs: Long = DOUBLE_TAP_WINDOW_MS): TapStep {
        val first = pending ?: return TapStep(TapState(breaks, atMs), stop = false)
        return if (atMs - first < windowMs) {
            TapStep(TapState(breaks, null), stop = true)
        } else {
            TapStep(TapState(breaks + first, atMs), stop = false)
        }
    }

    /** Time passed: commit the pending tap once its window is over. Returns this when nothing changes. */
    fun tick(nowMs: Long, windowMs: Long = DOUBLE_TAP_WINDOW_MS): TapState {
        val first = pending ?: return this
        return if (nowMs - first >= windowMs) TapState(breaks + first, null) else this
    }

    /** The breaks of a take stopped by other means (Stop button, silence): a pending tap counts. */
    fun finish(): List<Long> = pending?.let { breaks + it } ?: breaks

    companion object {
        val Initial: TapState = TapState(emptyList(), null)
    }
}

/** The result of a down: the next state, and whether it was a double tap. */
data class TapStep(val state: TapState, val stop: Boolean)
