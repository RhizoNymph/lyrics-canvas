package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreInvalidReason
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Grid stress 1 -> accent 1; anything else (0, 2, missing) -> 0. */
fun defaultAccent(stress: Int?): Int = if (stress == 1) 1 else 0

/**
 * A first score laid out cell by cell (spec section 12): each syllable one [note] length, each
 * rest one beat unit (`192 / den`), each line from a new bar; accents from stress.
 */
fun draftFromGrid(grid: GridData?, num: Int, den: Int, bpm: Double, note: ScoreSnap): ScoreResult<Score> {
    if (!ScoreRules.isValidMeter(num.toLong(), den.toLong())) return ScoreResult.Err(ScoreError.Invalid(ScoreInvalidReason.METER_INVALID, "meter $num/$den is not valid"))
    if (!bpm.isFinite() || !ScoreRules.isValidBpm(bpm)) return ScoreResult.Err(ScoreError.Invalid(ScoreInvalidReason.TEMPO_INVALID, "bpm $bpm is not valid"))
    val meter = ScoreMeter(0, num, den)
    val length = note.ticks
    val beat = Score.WHOLE / den
    val bar = meter.barLength.toLong()
    var cursor = 0L
    val lines = grid.scoreLines().mapIndexed { index, line ->
        if (index > 0) cursor = (cursor + bar - 1) / bar * bar
        var ordinal = 0
        val notes = mutableListOf<ScoreNote>()
        for (cell in line.cells) {
            if (cell.isRest) {
                cursor += beat
                continue
            }
            // Past MAX_TICKS the validation below refuses; clamp so Int never wraps first.
            notes += ScoreNote(ordinal++, cursor.coerceAtMost(Score.MAX_TICKS.toLong()).toInt(), length, defaultAccent(cell.stress))
            cursor += length
        }
        ScoreLine(notes)
    }
    val score = Score(pickup = 0, meters = listOf(meter), tempos = listOf(ScoreTempo(0, bpm)), snap = note, lines = lines)
    return validateScore(score, grid).orOk { score }
}

/** [draftFromGrid] with JSON arguments, checked in the spec's order (meter, bpm, note). */
fun draftFromGridRaw(grid: GridData?, meter: JsonElement?, bpm: JsonElement?, note: JsonElement?): ScoreResult<Score> {
    val obj = meter as? JsonObject
    val num = obj?.get("num").asInteger()
    val den = obj?.get("den").asInteger()
    if (num == null || den == null || !ScoreRules.isValidMeter(num, den)) {
        return ScoreResult.Err(ScoreError.Invalid(ScoreInvalidReason.METER_INVALID, "meter is not valid"))
    }
    val tempo = bpm.asNumber()
    if (tempo == null || !ScoreRules.isValidBpm(tempo)) return ScoreResult.Err(ScoreError.Invalid(ScoreInvalidReason.TEMPO_INVALID, "bpm $bpm is not valid"))
    val snap = (note as? JsonPrimitive)?.takeIf { it.isString }?.let { ScoreRules.snapOf(it.content) }
        ?: return ScoreResult.Err(ScoreError.Invalid(ScoreInvalidReason.SNAP_INVALID, "note $note is not a snap"))
    return draftFromGrid(grid, num.toInt(), den.toInt(), tempo, snap)
}
