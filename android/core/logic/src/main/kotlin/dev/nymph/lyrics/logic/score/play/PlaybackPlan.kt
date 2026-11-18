package dev.nymph.lyrics.logic.score.play

import dev.nymph.lyrics.logic.score.barLength
import dev.nymph.lyrics.logic.score.barStartTick
import dev.nymph.lyrics.logic.score.barCount
import dev.nymph.lyrics.logic.score.isCompound
import dev.nymph.lyrics.logic.score.meterAtBar
import dev.nymph.lyrics.logic.score.tickToMs
import dev.nymph.lyrics.logic.score.unit
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreAccent

/** A metronome click: [downbeat] on a bar line (louder, higher). */
data class Click(val ms: Double, val downbeat: Boolean)

/** One sounding note: from [startMs] for [durationMs], at [gain] (by accent); [line] / [s] name its syllable. */
data class PlayedNote(val startMs: Double, val durationMs: Double, val gain: Float, val line: Int, val s: Int)

/** What playback sounds, in time order, and how long it lasts (to the end of the last bar). */
data class PlaybackPlan(val clicks: List<Click>, val notes: List<PlayedNote>, val durationMs: Double, val endTick: Int)

/** Note gain per accent (none, accent, strong). */
fun accentGain(accent: Int): Float = when (accent) {
    ScoreAccent.STRONG -> 1.0f
    ScoreAccent.ACCENT -> 0.75f
    else -> 0.5f
}

/**
 * The beats of [score] from tick 0 to the end of its last bar: in a simple meter every
 * denominator note, in a compound one (spec section 6) every dotted beat, plus the pickup's beats
 * (counted back from bar 0 on a virtual bar of the first meter); then every note.
 */
fun playbackPlan(score: Score): PlaybackPlan {
    val bars = barCount(score)
    val clickTicks = mutableListOf<Pair<Int, Boolean>>()
    val first = score.meters[0]
    val firstBeat = if (first.isCompound) 3 * first.unit else first.unit
    val lead = first.barLength - score.pickup
    for (tick in 0 until score.pickup) {
        if ((lead + tick) % firstBeat == 0) clickTicks += tick to false
    }
    for (bar in 0 until bars) {
        val meter = meterAtBar(score, bar)
        val beat = if (meter.isCompound) 3 * meter.unit else meter.unit
        val start = barStartTick(score, bar).toInt()
        for (offset in 0 until barLength(score, bar) step beat) clickTicks += (start + offset) to (offset == 0)
    }
    val end = barStartTick(score, bars).toInt()
    val notes = score.lines.flatMapIndexed { line, l ->
        l.notes.map { n ->
            val start = tickToMs(score, n.t)
            PlayedNote(start, tickToMs(score, n.end) - start, accentGain(n.a), line, n.s)
        }
    }
    return PlaybackPlan(clickTicks.map { (t, down) -> Click(tickToMs(score, t), down) }, notes, tickToMs(score, end), end)
}
