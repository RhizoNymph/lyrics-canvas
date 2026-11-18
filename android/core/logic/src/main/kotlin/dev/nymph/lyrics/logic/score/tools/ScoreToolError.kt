package dev.nymph.lyrics.logic.score.tools

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.score.NOT_SYNCED_ERROR_CODE
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreExportFormat
import dev.nymph.lyrics.model.score.ScoreToolErrorCode
import java.util.Locale

/** Every way a score draft or export can fail, as the sheet / menu shows it. */
enum class ScoreToolErrorKind(val defaultMessage: String) {
    NEEDS_CONNECTION("Making a draft or exporting needs a connection"),
    NOT_SYNCED("Wait until this snippet's changes have synced, then try again"),
    SNIPPET_NOT_FOUND("This snippet is gone from the server"),
    SCORE_NOT_FOUND("This snippet has no score to export yet"),
    RECORDING_NOT_FOUND("That recording is gone"),
    RECORDING_HAS_NO_TIMINGS("This take has no word timings; record it again to draft from it"),
    DRAFT_EMPTY("Nothing to place: no syllables matched"),
    INVALID_SETTINGS("The server refused these settings"),
    NOT_FOUND("Not found on the server (it may need an update)"),
    UNAUTHORIZED("Sign in again"),
    NETWORK("Can't reach the server. Check your connection."),
    SERVER("Server error"),
    UNKNOWN("Unexpected response from the server"),
}

/** A typed score-tool failure; held as UI state, never thrown. */
data class ScoreToolError(val kind: ScoreToolErrorKind, val message: String, val status: Int? = null)

fun toScoreToolError(error: ApiError): ScoreToolError {
    fun of(kind: ScoreToolErrorKind, status: Int?) = ScoreToolError(kind, kind.defaultMessage, status)
    val code = ScoreToolErrorCode.parse(error.code)
    return when (error) {
        ApiError.NeedsConnection -> of(ScoreToolErrorKind.NEEDS_CONNECTION, null)
        is ApiError.Network -> of(ScoreToolErrorKind.NETWORK, null)
        is ApiError.Unauthorized -> of(ScoreToolErrorKind.UNAUTHORIZED, error.status)
        is ApiError.Decoding -> of(ScoreToolErrorKind.UNKNOWN, null)
        is ApiError.NotFound -> of(
            when (code) {
                ScoreToolErrorCode.SCORE_NOT_FOUND -> ScoreToolErrorKind.SCORE_NOT_FOUND
                ScoreToolErrorCode.SNIPPET_NOT_FOUND -> ScoreToolErrorKind.SNIPPET_NOT_FOUND
                ScoreToolErrorCode.RECORDING_NOT_FOUND -> ScoreToolErrorKind.RECORDING_NOT_FOUND
                else -> ScoreToolErrorKind.NOT_FOUND
            },
            404,
        )
        is ApiError.Validation -> of(
            when {
                error.code == NOT_SYNCED_ERROR_CODE -> ScoreToolErrorKind.NOT_SYNCED
                code == ScoreToolErrorCode.RECORDING_HAS_NO_TIMINGS -> ScoreToolErrorKind.RECORDING_HAS_NO_TIMINGS
                code == ScoreToolErrorCode.DRAFT_EMPTY -> ScoreToolErrorKind.DRAFT_EMPTY
                else -> ScoreToolErrorKind.INVALID_SETTINGS
            },
            error.status,
        )
        is ApiError.Server -> ScoreToolError(ScoreToolErrorKind.SERVER, error.message, error.status)
    }
}

private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun bpmText(bpm: Double): String =
    if (bpm % 1.0 == 0.0) bpm.toLong().toString() else String.format(Locale.US, "%.2f", bpm).trimEnd('0').trimEnd('.')

/** "Estimated tempo 101.5 bpm · 2 syllables unplaced · 1 word not matched", or "Every syllable placed". */
fun draftSummary(draft: ScoreDraftResponse): String {
    val parts = buildList {
        draft.bpmEstimated?.let { add("Estimated tempo ${bpmText(it)} bpm") }
        if (draft.unplaced > 0) add(count(draft.unplaced, "syllable", "syllables") + " unplaced")
        if (draft.unmatchedWords > 0) add(count(draft.unmatchedWords, "word", "words") + " not matched")
    }
    if (parts.isEmpty()) return "Every syllable placed"
    return if (draft.unplaced == 0 && draft.unmatchedWords == 0) (parts + "every syllable placed").joinToString(" · ") else parts.joinToString(" · ")
}

private val UNSAFE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

/**
 * The file name to share or save: the server's suggestion (unsafe characters as `_`, the
 * format's extension added when missing, any case accepted), else `<fallbackStem>.<ext>`.
 */
fun exportFileName(suggested: String?, format: ScoreExportFormat, fallbackStem: String): String {
    val clean = suggested?.replace(UNSAFE_NAME_CHARS, "_")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackStem
    return if (clean.lowercase().endsWith(".${format.extension}")) clean else "$clean.${format.extension}"
}
