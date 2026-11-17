package dev.nymph.lyrics.model.score

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Snippet score ("musical mode"), wire format v1: spec/score_format.md.
 *
 * Types only; the algorithms (validate, timing, rest link, reconcile, draft) are ported in
 * core/logic against spec/score_vectors.json. Mirrors frontend/src/types/score.ts.
 */
@Serializable
data class Score(
    val v: Int = VERSION,
    val ppq: Int = PPQ,
    /** Ticks before bar 0 (0 <= pickup < length of bar 0); tick 0 is the start of the pickup. */
    val pickup: Int = 0,
    /** Meter changes; the first is at bar 0, bars strictly increasing. */
    val meters: List<ScoreMeter>,
    /** Tempo changes at bar starts; the first is at bar 0, bars strictly increasing. */
    val tempos: List<ScoreTempo>,
    /** The editor's last snap choice (content, but it constrains nothing). */
    val snap: ScoreSnap,
    /** One entry per grid line (same index), rest-only lines included. */
    val lines: List<ScoreLine>,
) {
    companion object {
        const val VERSION: Int = 1
        const val PPQ: Int = 48
        const val WHOLE: Int = 4 * PPQ
        const val MAX_BAR: Int = 2048
        const val MAX_TICKS: Int = PPQ * 4 * MAX_BAR
        const val MAX_METERS: Int = 256
        const val MAX_TEMPOS: Int = 256
    }
}

/** A meter in force from [bar] until the next entry. [num] 1..32, [den] in {1, 2, 4, 8, 16, 32}. */
@Serializable
data class ScoreMeter(
    val bar: Int,
    val num: Int,
    val den: Int,
) {
    /** `num * 192 / den` ticks. */
    val barLength: Int get() = num * Score.WHOLE / den
}

/** A tempo from the start of [bar]: quarter notes per minute, 20..400 with at most 2 decimals. */
@Serializable
data class ScoreTempo(
    val bar: Int,
    val bpm: Double,
)

/** One entry of [Score.lines]. */
@Serializable
data class ScoreLine(
    val notes: List<ScoreNote> = emptyList(),
)

/** Snap values and their length in ticks. */
@Serializable
enum class ScoreSnap(val ticks: Int) {
    @SerialName("1/8") EIGHTH(24),
    @SerialName("1/16") SIXTEENTH(12),
    @SerialName("1/32") THIRTY_SECOND(6),
    @SerialName("1/8t") EIGHTH_TRIPLET(16),
    @SerialName("1/16t") SIXTEENTH_TRIPLET(8),
}

/** Accent values of [ScoreNote.a]. */
object ScoreAccent {
    const val NONE: Int = 0
    const val ACCENT: Int = 1
    const val STRONG: Int = 2
}

/**
 * Every `reason` of the 422 `invalid_score` error (its `detail` also has `line` / `note` for line and
 * note failures), in the order validate checks them.
 */
@Serializable
enum class ScoreInvalidReason {
    @SerialName("malformed") MALFORMED,
    @SerialName("unsupported_version") UNSUPPORTED_VERSION,
    @SerialName("bad_ppq") BAD_PPQ,
    @SerialName("meters_empty") METERS_EMPTY,
    @SerialName("too_many_meters") TOO_MANY_METERS,
    @SerialName("meters_first_not_zero") METERS_FIRST_NOT_ZERO,
    @SerialName("meters_not_increasing") METERS_NOT_INCREASING,
    @SerialName("bar_out_of_range") BAR_OUT_OF_RANGE,
    @SerialName("meter_invalid") METER_INVALID,
    @SerialName("tempos_empty") TEMPOS_EMPTY,
    @SerialName("too_many_tempos") TOO_MANY_TEMPOS,
    @SerialName("tempos_first_not_zero") TEMPOS_FIRST_NOT_ZERO,
    @SerialName("tempos_not_increasing") TEMPOS_NOT_INCREASING,
    @SerialName("tempo_invalid") TEMPO_INVALID,
    @SerialName("bad_pickup") BAD_PICKUP,
    @SerialName("snap_invalid") SNAP_INVALID,
    @SerialName("line_count_mismatch") LINE_COUNT_MISMATCH,
    @SerialName("syllable_out_of_range") SYLLABLE_OUT_OF_RANGE,
    @SerialName("syllables_not_increasing") SYLLABLES_NOT_INCREASING,
    @SerialName("start_negative") START_NEGATIVE,
    @SerialName("duration_invalid") DURATION_INVALID,
    @SerialName("accent_invalid") ACCENT_INVALID,
    @SerialName("exceeds_max_ticks") EXCEEDS_MAX_TICKS,
    @SerialName("notes_overlap") NOTES_OVERLAP,
}

/** The `detail` of a 422 `invalid_score` response. */
@Serializable
data class ScoreInvalidDetail(
    val error: String,
    val reason: ScoreInvalidReason,
    val line: Int? = null,
    val note: Int? = null,
)
