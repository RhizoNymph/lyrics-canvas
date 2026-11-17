package dev.nymph.lyrics.logic.score.edit

import dev.nymph.lyrics.logic.score.ScoreRules
import dev.nymph.lyrics.logic.score.endTick
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlin.math.roundToLong

/**
 * Edits of a score's bar structure: meter and tempo changes at a bar, the pickup and the snap.
 * Notes keep their absolute ticks under meter and tempo edits (they are a rhythm, the bar lines
 * move under them); the pickup moves them with bar 0 (see [setPickup]).
 */

/** Set the meter from [bar] on (a change at that bar, or bar 0's meter). The pickup is cut to fit bar 0. */
fun setMeter(score: Score, bar: Int, num: Int, den: Int): StaffEdit {
    if (bar !in 0..Score.MAX_BAR || !ScoreRules.isValidMeter(num.toLong(), den.toLong())) return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    val others = score.meters.filter { it.bar != bar }
    if (others.size >= Score.MAX_METERS) return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    val meters = (others + ScoreMeter(bar, num, den)).sortedBy { it.bar }
    val pickup = score.pickup.coerceAtMost(meters[0].barLength - 1)
    return StaffEdit.Changed(score.copy(meters = meters, pickup = pickup))
}

/** Remove the meter change at [bar] (bar 0 has no "change" to remove). */
fun removeMeter(score: Score, bar: Int): StaffEdit = when {
    bar == 0 -> StaffEdit.Refused(StaffRefusal.FIRST_ENTRY)
    score.meters.none { it.bar == bar } -> StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    else -> StaffEdit.Changed(score.copy(meters = score.meters.filter { it.bar != bar }))
}

/** [bpm] rounded to two decimals; null outside 20..400. */
fun tempoValue(bpm: Double): Double? {
    if (!bpm.isFinite()) return null
    val rounded = (bpm * 100).roundToLong() / 100.0
    return rounded.takeIf { ScoreRules.isValidBpm(it) }
}

/** Set the tempo from the start of [bar] (bars hold a tempo until the next change). */
fun setTempo(score: Score, bar: Int, bpm: Double): StaffEdit {
    val value = tempoValue(bpm) ?: return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    if (bar !in 0..Score.MAX_BAR) return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    val others = score.tempos.filter { it.bar != bar }
    if (others.size >= Score.MAX_TEMPOS) return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    return StaffEdit.Changed(score.copy(tempos = (others + ScoreTempo(bar, value)).sortedBy { it.bar }))
}

fun removeTempo(score: Score, bar: Int): StaffEdit = when {
    bar == 0 -> StaffEdit.Refused(StaffRefusal.FIRST_ENTRY)
    score.tempos.none { it.bar == bar } -> StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    else -> StaffEdit.Changed(score.copy(tempos = score.tempos.filter { it.bar != bar }))
}

/**
 * Set the pickup to [ticks] (0 <= ticks < bar 0's length). Every note moves by the change, so it
 * keeps its place relative to the bar lines (a draft that started on the downbeat still does);
 * shrinking stops where the first note would go below tick 0, growing where the last would pass
 * MAX_TICKS. The pickup moves by the same (clamped) amount.
 */
fun setPickup(score: Score, ticks: Int): StaffEdit {
    val length = score.meters[0].barLength
    if (ticks !in 0 until length) return StaffEdit.Refused(StaffRefusal.OUT_OF_RANGE)
    val first = score.lines.flatMap { it.notes }.minOfOrNull { it.t }
    var delta = ticks - score.pickup
    if (first != null) delta = delta.coerceIn(-first, Score.MAX_TICKS - endTick(score))
    if (delta == 0) return StaffEdit.Changed(score)
    val lines = score.lines.map { line -> ScoreLine(line.notes.map { it.copy(t = it.t + delta) }) }
    return StaffEdit.Changed(score.copy(pickup = score.pickup + delta, lines = lines))
}

fun setSnap(score: Score, snap: ScoreSnap): Score = score.copy(snap = snap)
