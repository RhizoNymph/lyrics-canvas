package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreTempo

/** Bars, beats and time of a valid score (spec/score_format.md sections 5-7). */

/** Bar -1 is the pickup: start 0, length `pickup`. */
const val PICKUP_BAR: Int = -1

/** A tick's bar (-1 in the pickup) and its offset from that bar's start. */
data class BarPosition(val bar: Int, val offset: Int)

/** One denominator note in ticks (`192 / den`): the beat unit of the rest link. */
val ScoreMeter.unit: Int get() = Score.WHOLE / den

/** `num % 3 == 0 && num >= 6 && den >= 8` (6/8, 9/8, 12/8, 6/16 ...). */
val ScoreMeter.isCompound: Boolean get() = num % 3 == 0 && num >= 6 && den >= 8

/** `M(bar)`: the last meter entry at or before [bar] (the pickup uses the first). */
fun meterAtBar(score: Score, bar: Int): ScoreMeter = score.meters.lastOrNull { it.bar <= bar } ?: score.meters[0]

/** The tempo in force at [bar] (the pickup uses the first). */
fun tempoAtBar(score: Score, bar: Int): ScoreTempo = score.tempos.lastOrNull { it.bar <= bar } ?: score.tempos[0]

fun barLength(score: Score, bar: Int): Int {
    require(bar >= PICKUP_BAR) { "bar $bar < -1" }
    return if (bar == PICKUP_BAR) score.pickup else meterAtBar(score, bar).barLength
}

/** `pickup + L(0) + ... + L(bar-1)`, summed per meter segment; bar -1 starts at 0. */
fun barStartTick(score: Score, bar: Int): Long {
    require(bar >= PICKUP_BAR) { "bar $bar < -1" }
    if (bar == PICKUP_BAR) return 0
    var tick = score.pickup.toLong()
    val meters = score.meters
    for ((index, meter) in meters.withIndex()) {
        val segmentEnd = meters.getOrNull(index + 1)?.bar
        if (segmentEnd == null || bar <= segmentEnd) return tick + (bar - meter.bar).toLong() * meter.barLength
        tick += (segmentEnd - meter.bar).toLong() * meter.barLength
    }
    error("unreachable: the last meter segment is unbounded")
}

fun barAtTick(score: Score, tick: Long): BarPosition {
    require(tick >= 0) { "tick $tick < 0" }
    if (tick < score.pickup) return BarPosition(PICKUP_BAR, tick.toInt())
    var start = score.pickup.toLong()
    val meters = score.meters
    for ((index, meter) in meters.withIndex()) {
        val length = meter.barLength.toLong()
        val next = meters.getOrNull(index + 1)
        if (next != null) {
            val bars = (next.bar - meter.bar).toLong()
            if (tick < start + bars * length) {
                val into = (tick - start) / length
                return BarPosition((meter.bar + into).toInt(), (tick - start - into * length).toInt())
            }
            start += bars * length
        } else {
            val into = (tick - start) / length
            return BarPosition((meter.bar + into).toInt(), (tick - start - into * length).toInt())
        }
    }
    error("unreachable: the last meter segment is unbounded")
}

fun barAtTick(score: Score, tick: Int): BarPosition = barAtTick(score, tick.toLong())

/** The meter in force at [tick]: `M(bar)`, or the first meter in the pickup. */
fun meterAt(score: Score, tick: Int): ScoreMeter {
    val position = barAtTick(score, tick)
    return if (position.bar == PICKUP_BAR) score.meters[0] else meterAtBar(score, position.bar)
}

/** `192 / den` of the meter in force at [tick]. */
fun beatUnit(score: Score, tick: Int): Int = meterAt(score, tick).unit

/** 3 bar downbeat, 2 on a beat, 1 on a half beat (compound: on a subdivision), 0 otherwise. */
fun metricWeight(score: Score, tick: Int): Int {
    val position = barAtTick(score, tick)
    val meter: ScoreMeter
    val offset: Int
    if (position.bar == PICKUP_BAR) {
        meter = score.meters[0]
        offset = meter.barLength - score.pickup + tick
    } else {
        meter = meterAtBar(score, position.bar)
        offset = position.offset
    }
    if (offset == 0) return 3
    val unit = meter.unit
    val beat = if (meter.isCompound) 3 * unit else unit
    val sub = if (meter.isCompound) unit else unit / 2
    if (offset % beat == 0) return 2
    if (offset % sub == 0) return 1
    return 0
}

/** Milliseconds from tick 0, by the exact summation of spec section 7 (same bits on every port). */
fun tickToMs(score: Score, tick: Int): Double {
    require(tick >= 0) { "tick $tick < 0" }
    val starts = listOf(0L) + score.tempos.drop(1).map { barStartTick(score, it.bar) }
    var ms = 0.0
    for ((index, tempo) in score.tempos.withIndex()) {
        val start = starts[index]
        if (tick <= start) break
        val end = starts.getOrNull(index + 1)
        val ticks = if (end == null) tick - start else minOf(tick.toLong(), end) - start
        ms = ms + (ticks.toDouble() * 60000.0) / (tempo.bpm * 48.0)
    }
    return ms
}

/** The tick at [ms] (inverse of [tickToMs], floored, >= 0): for playheads. */
fun msToTick(score: Score, ms: Double): Int {
    if (ms <= 0.0) return 0
    var lo = 0
    var hi = Score.MAX_TICKS * 2
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (tickToMs(score, mid) <= ms) lo = mid else hi = mid - 1
    }
    return lo
}

/** `max(t + d)` over all notes, 0 when there are none. */
fun endTick(score: Score): Int = score.lines.flatMap { it.notes }.maxOfOrNull { it.end } ?: 0

/** Bars from bar 0 needed to hold every note, at least 1 (the pickup is not counted). */
fun barCount(score: Score): Int {
    val position = barAtTick(score, endTick(score))
    if (position.bar == PICKUP_BAR) return 1
    return maxOf(1, position.bar + if (position.offset > 0) 1 else 0)
}
