package dev.nymph.lyrics.logic.score.edit

import dev.nymph.lyrics.logic.score.barAtTick
import dev.nymph.lyrics.logic.score.barLength
import dev.nymph.lyrics.logic.score.barStartTick
import dev.nymph.lyrics.model.score.Score
import kotlin.math.abs

/**
 * The snap grid point nearest to [tick] (>= 0), [snap] ticks apart. The grid restarts at every bar
 * line (so triplets and odd meters stay aligned to their bar), and in the pickup it runs backwards
 * from the start of bar 0. Ties go to the earlier point. The bar line after a bar is always a
 * candidate, so a tick near the end of a bar whose length is not a multiple of [snap] snaps there.
 */
fun snapTick(score: Score, tick: Int, snap: Int): Int {
    require(snap >= 1) { "snap must be at least 1 tick" }
    val t = tick.coerceAtLeast(0)
    val pos = barAtTick(score, t)
    if (pos.bar == -1) {
        // Points pickup, pickup - snap, ... and 0 (the start of the pickup).
        val back = score.pickup - t
        val above = score.pickup - (back / snap) * snap
        val below = (score.pickup - ((back + snap - 1) / snap) * snap).coerceAtLeast(0)
        return nearest(t, listOf(below, above))
    }
    val start = barStartTick(score, pos.bar).toInt()
    val length = barLength(score, pos.bar)
    val below = start + (pos.offset / snap) * snap
    val above = (below + snap).coerceAtMost(start + length)
    return nearest(t, listOf(below, above))
}

/** A length snapped to whole [snap] units, at least one unit. */
fun snapLength(length: Int, snap: Int): Int {
    require(snap >= 1) { "snap must be at least 1 tick" }
    val units = (length + snap / 2) / snap
    return maxOf(1, units) * snap
}

private fun nearest(t: Int, candidates: List<Int>): Int =
    candidates.distinct().sorted().minByOrNull { abs(it - t) } ?: t
