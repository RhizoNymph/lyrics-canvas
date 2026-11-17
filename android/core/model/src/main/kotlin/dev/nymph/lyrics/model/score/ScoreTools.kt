package dev.nymph.lyrics.model.score

import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire model of the score draft and export routes (round-4 contract section 5):
 * `POST /api/snippets/{id}/score/draft` and `GET /api/snippets/{id}/score/export?format=`.
 */

/** The meter of a draft (`{num, den}`): [num] 1..32, [den] in {1, 2, 4, 8, 16, 32} (checked by the caller). */
@Serializable
data class DraftMeter(val num: Int, val den: Int)

/**
 * Body of `POST /api/snippets/{id}/score/draft`. Encoded with [toJson] (not a generated
 * serializer) so a recording draft always sends `bpm`, null meaning "estimate it", whatever the
 * shared Json's `explicitNulls` setting.
 */
sealed interface ScoreDraftRequest {
    val meter: DraftMeter

    /** Every grid cell in sequence: syllables take one [note] length, rests one beat unit. */
    data class FromGrid(val note: ScoreSnap, override val meter: DraftMeter, val bpm: Double) : ScoreDraftRequest

    /** From a take's word timings; [bpm] null asks the server to estimate the tempo. */
    data class FromRecording(val recordingId: RecordingId, override val meter: DraftMeter, val bpm: Double?) : ScoreDraftRequest

    fun toJson(): JsonObject = buildJsonObject {
        when (this@ScoreDraftRequest) {
            is FromGrid -> {
                put("source", "grid")
                put("note", LyricsJson.encodeToJsonElement(ScoreSnap.serializer(), note))
                put("meter", LyricsJson.encodeToJsonElement(DraftMeter.serializer(), meter))
                put("bpm", bpm)
            }
            is FromRecording -> {
                put("source", "recording")
                put("recording_id", recordingId.value)
                put("meter", LyricsJson.encodeToJsonElement(DraftMeter.serializer(), meter))
                put("bpm", bpm?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }
}

/** The draft answer; nothing is saved (the client shows it and saves it with the snippet). */
@Serializable
data class ScoreDraftResponse(
    val score: Score,
    /** The tempo the server estimated from the take's onsets; null when one was given (or for a grid draft). */
    @SerialName("bpm_estimated") val bpmEstimated: Double? = null,
    /** Syllables left without a note (shown in the editor's tray). */
    val unplaced: Int = 0,
    /** Grid words no take word was aligned to (recording drafts). */
    @SerialName("unmatched_words") val unmatchedWords: Int = 0,
)

/** The export formats: the `format` query value, the file extension and the mime type of the file. */
enum class ScoreExportFormat(val wire: String, val extension: String, val mime: String, val label: String) {
    MIDI("midi", "mid", "audio/midi", "MIDI"),
    MUSICXML("musicxml", "musicxml", "application/vnd.recordare.musicxml+xml", "MusicXML"),
}

/**
 * The `error` codes the score draft / export routes put in `{"detail": {"error": code, ...}}`.
 * Unknown codes parse to null.
 */
enum class ScoreToolErrorCode(val wire: String) {
    /** 404: the snippet is missing or not the caller's. */
    SNIPPET_NOT_FOUND("snippet_not_found"),

    /** 404: export of a snippet without a score. */
    SCORE_NOT_FOUND("score_not_found"),

    /** 404: the recording is missing, not the caller's, or not on this snippet. */
    RECORDING_NOT_FOUND("recording_not_found"),

    /** 422: the take has no stored word timings. */
    RECORDING_HAS_NO_TIMINGS("recording_has_no_timings"),

    /** 422: nothing to place (an empty grid, or no take word matched). */
    DRAFT_EMPTY("draft_empty"),

    /** 422: the meter / bpm / note were rejected, or the result would be an invalid score. */
    INVALID_SCORE("invalid_score"),
    ;

    companion object {
        fun parse(code: String?): ScoreToolErrorCode? = entries.firstOrNull { it.wire == code }
    }
}

/**
 * The client-side code of a draft / export refused because the snippet's local changes have not
 * reached the server yet (the server never sends it).
 */
const val NOT_SYNCED_ERROR_CODE: String = "not_synced"
