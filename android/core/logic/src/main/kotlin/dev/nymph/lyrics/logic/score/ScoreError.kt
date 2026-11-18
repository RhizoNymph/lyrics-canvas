package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.ScoreInvalidDetail
import dev.nymph.lyrics.model.score.ScoreInvalidReason

/** Why a score algorithm refused (spec/score_format.md); values, never thrown. */
sealed interface ScoreError {
    val message: String

    /** The score breaks format v1 or its grid: the server's 422 `invalid_score` with [reason]. */
    data class Invalid(
        val reason: ScoreInvalidReason,
        override val message: String,
        val line: Int? = null,
        val note: Int? = null,
    ) : ScoreError {
        /** The wire shape `{error: "invalid_score", reason, line?, note?}`. */
        fun detail(): ScoreInvalidDetail = ScoreInvalidDetail(INVALID_SCORE, reason, line, note)
    }

    /** apply_grid_rest_edit was given an edit that changed syllables or lines, not only rests. */
    data class NotRestOnly(override val message: String) : ScoreError

    /** splice_line_texts named grid line [index]; the text has only [available] non-blank lines. */
    data class TextMismatch(val index: Int, val available: Int) : ScoreError {
        override val message: String
            get() = "grid line $index has no text line (text has $available non-blank lines)"
    }

    companion object {
        const val INVALID_SCORE: String = "invalid_score"
    }
}

/** An algorithm's answer: [Ok] with the value or [Err] with a typed [ScoreError]. */
sealed interface ScoreResult<out T> {
    data class Ok<out T>(val value: T) : ScoreResult<T>

    data class Err(val error: ScoreError) : ScoreResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): ScoreError? = (this as? Err)?.error
}

inline fun <T, R> ScoreResult<T>.map(transform: (T) -> R): ScoreResult<R> = when (this) {
    is ScoreResult.Ok -> ScoreResult.Ok(transform(value))
    is ScoreResult.Err -> this
}

inline fun <T, R> ScoreResult<T>.flatMap(transform: (T) -> ScoreResult<R>): ScoreResult<R> = when (this) {
    is ScoreResult.Ok -> transform(value)
    is ScoreResult.Err -> this
}

internal fun <T> ScoreError.Invalid?.orOk(value: () -> T): ScoreResult<T> =
    if (this == null) ScoreResult.Ok(value()) else ScoreResult.Err(this)
