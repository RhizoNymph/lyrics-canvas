package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.round

/** Fixed numbers of format v1 beyond [Score]'s constants (spec/score_format.md section 1). */
object ScoreRules {
    const val MAX_SAFE_INT: Long = 9007199254740991L
    const val NUM_MIN: Int = 1
    const val NUM_MAX: Int = 32
    val DENS: Set<Int> = setOf(1, 2, 4, 8, 16, 32)
    const val BPM_MIN: Double = 20.0
    const val BPM_MAX: Double = 400.0
    const val BPM_DECIMALS_TOLERANCE: Double = 1e-6
    val ACCENTS: Set<Int> = setOf(0, 1, 2)
    val SCORE_KEYS: Set<String> = setOf("v", "ppq", "pickup", "meters", "tempos", "snap", "lines")
    val METER_KEYS: Set<String> = setOf("bar", "num", "den")
    val TEMPO_KEYS: Set<String> = setOf("bar", "bpm")
    val LINE_KEYS: Set<String> = setOf("notes")
    val NOTE_KEYS: List<String> = listOf("s", "t", "d", "a")

    fun isValidMeter(num: Long, den: Long): Boolean = num in NUM_MIN..NUM_MAX && den.toInt().toLong() == den && den.toInt() in DENS

    /** 20..400 with at most two decimals (`|bpm*100 - round(bpm*100)| <= 1e-6`). */
    fun isValidBpm(bpm: Double): Boolean {
        if (bpm < BPM_MIN || bpm > BPM_MAX) return false
        val scaled = bpm * 100
        return abs(scaled - round(scaled)) <= BPM_DECIMALS_TOLERANCE
    }

    /** The snap a wire string names, or null. */
    fun snapOf(value: String): ScoreSnap? = ScoreSnap.entries.firstOrNull { it.wire == value }
}

/** The wire string of a snap (`"1/16"` ...). */
val ScoreSnap.wire: String
    get() = when (this) {
        ScoreSnap.EIGHTH -> "1/8"
        ScoreSnap.SIXTEENTH -> "1/16"
        ScoreSnap.THIRTY_SECOND -> "1/32"
        ScoreSnap.EIGHTH_TRIPLET -> "1/8t"
        ScoreSnap.SIXTEENTH_TRIPLET -> "1/16t"
    }

/** The integer a JSON value denotes (spec section 2, Integers): an integral number, not a boolean, within ±(2^53 - 1). */
internal fun JsonElement?.asInteger(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString) return null
    val content = p.content
    if (content == "true" || content == "false" || content == "null") return null
    content.toLongOrNull()?.let { return it.takeIf { v -> abs(v) <= ScoreRules.MAX_SAFE_INT } }
    val d = content.toDoubleOrNull() ?: return null
    if (!d.isFinite() || d % 1.0 != 0.0 || abs(d) > ScoreRules.MAX_SAFE_INT.toDouble()) return null
    return d.toLong()
}

/** A JSON number (not a boolean or string) as a double, or null. */
internal fun JsonElement?.asNumber(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString) return null
    val content = p.content
    if (content == "true" || content == "false" || content == "null") return null
    return content.toDoubleOrNull()?.takeIf { it.isFinite() }
}
