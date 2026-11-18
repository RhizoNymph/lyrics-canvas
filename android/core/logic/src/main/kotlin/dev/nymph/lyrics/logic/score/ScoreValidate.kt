package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreInvalidReason
import dev.nymph.lyrics.model.score.ScoreInvalidReason.ACCENT_INVALID
import dev.nymph.lyrics.model.score.ScoreInvalidReason.BAD_PICKUP
import dev.nymph.lyrics.model.score.ScoreInvalidReason.BAD_PPQ
import dev.nymph.lyrics.model.score.ScoreInvalidReason.BAR_OUT_OF_RANGE
import dev.nymph.lyrics.model.score.ScoreInvalidReason.DURATION_INVALID
import dev.nymph.lyrics.model.score.ScoreInvalidReason.EXCEEDS_MAX_TICKS
import dev.nymph.lyrics.model.score.ScoreInvalidReason.LINE_COUNT_MISMATCH
import dev.nymph.lyrics.model.score.ScoreInvalidReason.MALFORMED
import dev.nymph.lyrics.model.score.ScoreInvalidReason.METERS_EMPTY
import dev.nymph.lyrics.model.score.ScoreInvalidReason.METERS_FIRST_NOT_ZERO
import dev.nymph.lyrics.model.score.ScoreInvalidReason.METERS_NOT_INCREASING
import dev.nymph.lyrics.model.score.ScoreInvalidReason.METER_INVALID
import dev.nymph.lyrics.model.score.ScoreInvalidReason.NOTES_OVERLAP
import dev.nymph.lyrics.model.score.ScoreInvalidReason.SNAP_INVALID
import dev.nymph.lyrics.model.score.ScoreInvalidReason.START_NEGATIVE
import dev.nymph.lyrics.model.score.ScoreInvalidReason.SYLLABLES_NOT_INCREASING
import dev.nymph.lyrics.model.score.ScoreInvalidReason.SYLLABLE_OUT_OF_RANGE
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TEMPOS_EMPTY
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TEMPOS_FIRST_NOT_ZERO
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TEMPOS_NOT_INCREASING
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TEMPO_INVALID
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TOO_MANY_METERS
import dev.nymph.lyrics.model.score.ScoreInvalidReason.TOO_MANY_TEMPOS
import dev.nymph.lyrics.model.score.ScoreInvalidReason.UNSUPPORTED_VERSION
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown only inside this file to stop at the first failure; never escapes [checkScore]. */
private class Stop(val error: ScoreError.Invalid) : RuntimeException(null, null, false, false)

private fun stop(reason: ScoreInvalidReason, message: String, line: Int? = null, note: Int? = null): Nothing =
    throw Stop(ScoreError.Invalid(reason, message, line, note))

private fun JsonElement?.keysExactly(keys: Set<String>): JsonObject? = (this as? JsonObject)?.takeIf { it.keys == keys }

private fun checkBar(bar: Long, index: Int, previous: Long?, first: ScoreInvalidReason, order: ScoreInvalidReason, what: String) {
    if (index == 0 && bar != 0L) stop(first, "the first $what must be at bar 0, got $bar")
    if (previous != null && bar <= previous) stop(order, "$what $index at bar $bar is not after bar $previous")
    if (bar > Score.MAX_BAR) stop(BAR_OUT_OF_RANGE, "$what $index at bar $bar is past bar ${Score.MAX_BAR}")
}

private fun meters(value: JsonElement?): List<ScoreMeter> {
    if (value !is JsonArray) stop(MALFORMED, "meters must be an array")
    if (value.isEmpty()) stop(METERS_EMPTY, "meters must have an entry at bar 0")
    if (value.size > Score.MAX_METERS) stop(TOO_MANY_METERS, "at most ${Score.MAX_METERS} meters, got ${value.size}")
    val out = mutableListOf<ScoreMeter>()
    value.forEachIndexed { index, raw ->
        val entry = raw.keysExactly(ScoreRules.METER_KEYS)
        val bar = entry?.get("bar").asInteger()
        val num = entry?.get("num").asInteger()
        val den = entry?.get("den").asInteger()
        if (bar == null || num == null || den == null) stop(MALFORMED, "meter $index must be {bar, num, den} integers")
        checkBar(bar, index, out.lastOrNull()?.bar?.toLong(), METERS_FIRST_NOT_ZERO, METERS_NOT_INCREASING, "meter")
        if (!ScoreRules.isValidMeter(num, den)) stop(METER_INVALID, "meter $index is $num/$den")
        out += ScoreMeter(bar.toInt(), num.toInt(), den.toInt())
    }
    return out
}

private fun tempos(value: JsonElement?): List<ScoreTempo> {
    if (value !is JsonArray) stop(MALFORMED, "tempos must be an array")
    if (value.isEmpty()) stop(TEMPOS_EMPTY, "tempos must have an entry at bar 0")
    if (value.size > Score.MAX_TEMPOS) stop(TOO_MANY_TEMPOS, "at most ${Score.MAX_TEMPOS} tempos, got ${value.size}")
    val out = mutableListOf<ScoreTempo>()
    value.forEachIndexed { index, raw ->
        val entry = raw.keysExactly(ScoreRules.TEMPO_KEYS)
        val bar = entry?.get("bar").asInteger()
        val bpm = entry?.get("bpm").asNumber()
        if (bar == null || bpm == null) stop(MALFORMED, "tempo $index must be {bar: integer, bpm: number}")
        checkBar(bar, index, out.lastOrNull()?.bar?.toLong(), TEMPOS_FIRST_NOT_ZERO, TEMPOS_NOT_INCREASING, "tempo")
        if (!ScoreRules.isValidBpm(bpm)) stop(TEMPO_INVALID, "tempo $index bpm $bpm is not 20..400 with 2 decimals")
        out += ScoreTempo(bar.toInt(), bpm)
    }
    return out
}

/** A note's numbers before range checks (Long: the checks run before narrowing to Int). */
private class RawNote(val s: Long, val t: Long, val d: Long, val a: Long, val extra: Map<String, JsonElement>)

private fun note(raw: JsonElement, line: Int, index: Int): RawNote {
    if (raw !is JsonObject || ScoreRules.NOTE_KEYS.any { it !in raw }) stop(MALFORMED, "a note must be an object with s, t, d, a", line, index)
    val n = ScoreRules.NOTE_KEYS.map { raw[it].asInteger() }
    if (n.any { it == null }) stop(MALFORMED, "s, t, d, a must be integers", line, index)
    return RawNote(n[0]!!, n[1]!!, n[2]!!, n[3]!!, raw.filterKeys { it !in ScoreRules.NOTE_KEYS })
}

/** The note checks of spec section 4 step 10 (2..8), shared by the raw and typed paths. */
private fun checkNote(s: Long, t: Long, d: Long, a: Long, previousS: Long?, prevEnd: Long, count: Int, line: Int, index: Int) {
    if (s < 0 || s >= count) stop(SYLLABLE_OUT_OF_RANGE, "s $s not in 0..${count - 1}", line, index)
    if (previousS != null && s <= previousS) stop(SYLLABLES_NOT_INCREASING, "s $s after s $previousS", line, index)
    if (t < 0) stop(START_NEGATIVE, "t $t < 0", line, index)
    if (d < 1) stop(DURATION_INVALID, "d $d < 1", line, index)
    if (a !in 0L..2L) stop(ACCENT_INVALID, "a $a not 0, 1 or 2", line, index)
    if (t + d > Score.MAX_TICKS) stop(EXCEEDS_MAX_TICKS, "t + d = ${t + d} > ${Score.MAX_TICKS}", line, index)
    if (t < prevEnd) stop(NOTES_OVERLAP, "t $t starts before the previous note ends at $prevEnd", line, index)
}

private fun lines(value: JsonElement?, counts: List<Int>): List<ScoreLine> {
    if (value !is JsonArray) stop(MALFORMED, "lines must be an array")
    if (value.size != counts.size) stop(LINE_COUNT_MISMATCH, "the score has ${value.size} lines, the grid ${counts.size}")
    var prevEnd = 0L
    return value.mapIndexed { lineIndex, rawLine ->
        val entry = rawLine.keysExactly(ScoreRules.LINE_KEYS)
        val notes = entry?.get("notes") as? JsonArray ?: stop(MALFORMED, "a line must be {notes: [...]}", lineIndex)
        var previousS: Long? = null
        ScoreLine(
            notes.mapIndexed { noteIndex, rawNote ->
                val n = note(rawNote, lineIndex, noteIndex)
                checkNote(n.s, n.t, n.d, n.a, previousS, prevEnd, counts[lineIndex], lineIndex, noteIndex)
                previousS = n.s
                prevEnd = n.t + n.d
                ScoreNote(n.s.toInt(), n.t.toInt(), n.d.toInt(), n.a.toInt(), n.extra)
            },
        )
    }
}

/**
 * The typed score [raw] denotes when it is valid against [grid] (spec/score_format.md section 4),
 * or the first failure in the spec's order. Extra note keys are kept in [ScoreNote.extra].
 */
fun checkScore(raw: JsonElement, grid: GridData?): ScoreResult<Score> = try {
    val root = raw.keysExactly(ScoreRules.SCORE_KEYS) ?: stop(MALFORMED, "a score is an object with exactly ${ScoreRules.SCORE_KEYS}")
    val version = root["v"].asInteger() ?: stop(MALFORMED, "v must be an integer")
    if (version != Score.VERSION.toLong()) stop(UNSUPPORTED_VERSION, "v $version is not ${Score.VERSION}")
    val ppq = root["ppq"].asInteger() ?: stop(MALFORMED, "ppq must be an integer")
    if (ppq != Score.PPQ.toLong()) stop(BAD_PPQ, "ppq $ppq is not ${Score.PPQ}")
    val meters = meters(root["meters"])
    val tempos = tempos(root["tempos"])
    val pickup = root["pickup"].asInteger() ?: stop(MALFORMED, "pickup must be an integer")
    if (pickup < 0 || pickup >= meters[0].barLength) stop(BAD_PICKUP, "pickup $pickup not in 0..${meters[0].barLength - 1}")
    val snapValue = root["snap"] as? JsonPrimitive
    val snap = snapValue?.takeIf { it.isString }?.let { ScoreRules.snapOf(it.content) } ?: stop(SNAP_INVALID, "snap must be one of 1/8, 1/16, 1/32, 1/8t, 1/16t")
    val lines = lines(root["lines"], syllableCounts(grid))
    ScoreResult.Ok(Score(pickup = pickup.toInt(), meters = meters, tempos = tempos, snap = snap, lines = lines))
} catch (e: Stop) {
    ScoreResult.Err(e.error)
}

/** The first failure of a typed [score] against [grid] (the checks of section 4 a typed value can fail), or null. */
fun validateScore(score: Score, grid: GridData?): ScoreError.Invalid? = try {
    if (score.v != Score.VERSION) stop(UNSUPPORTED_VERSION, "v ${score.v} is not ${Score.VERSION}")
    if (score.ppq != Score.PPQ) stop(BAD_PPQ, "ppq ${score.ppq} is not ${Score.PPQ}")
    if (score.meters.isEmpty()) stop(METERS_EMPTY, "meters must have an entry at bar 0")
    if (score.meters.size > Score.MAX_METERS) stop(TOO_MANY_METERS, "at most ${Score.MAX_METERS} meters")
    score.meters.forEachIndexed { i, m ->
        checkBar(m.bar.toLong(), i, score.meters.getOrNull(i - 1)?.bar?.toLong(), METERS_FIRST_NOT_ZERO, METERS_NOT_INCREASING, "meter")
        if (!ScoreRules.isValidMeter(m.num.toLong(), m.den.toLong())) stop(METER_INVALID, "meter $i is ${m.num}/${m.den}")
    }
    if (score.tempos.isEmpty()) stop(TEMPOS_EMPTY, "tempos must have an entry at bar 0")
    if (score.tempos.size > Score.MAX_TEMPOS) stop(TOO_MANY_TEMPOS, "at most ${Score.MAX_TEMPOS} tempos")
    score.tempos.forEachIndexed { i, t ->
        checkBar(t.bar.toLong(), i, score.tempos.getOrNull(i - 1)?.bar?.toLong(), TEMPOS_FIRST_NOT_ZERO, TEMPOS_NOT_INCREASING, "tempo")
        if (!t.bpm.isFinite() || !ScoreRules.isValidBpm(t.bpm)) stop(TEMPO_INVALID, "tempo $i bpm ${t.bpm} is not 20..400 with 2 decimals")
    }
    if (score.pickup < 0 || score.pickup >= score.meters[0].barLength) stop(BAD_PICKUP, "pickup ${score.pickup} not in 0..${score.meters[0].barLength - 1}")
    val counts = syllableCounts(grid)
    if (score.lines.size != counts.size) stop(LINE_COUNT_MISMATCH, "the score has ${score.lines.size} lines, the grid ${counts.size}")
    var prevEnd = 0L
    score.lines.forEachIndexed { li, line ->
        var previousS: Long? = null
        line.notes.forEachIndexed { ni, n ->
            checkNote(n.s.toLong(), n.t.toLong(), n.d.toLong(), n.a.toLong(), previousS, prevEnd, counts[li], li, ni)
            previousS = n.s.toLong()
            prevEnd = n.t.toLong() + n.d
        }
    }
    null
} catch (e: Stop) {
    e.error
}
