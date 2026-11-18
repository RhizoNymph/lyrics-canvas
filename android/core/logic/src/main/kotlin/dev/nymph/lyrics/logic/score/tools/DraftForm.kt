package dev.nymph.lyrics.logic.score.tools

import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where a server draft comes from. */
enum class DraftSource(val label: String) {
    GRID("From grid"),
    RECORDING("From a recording"),
}

/** The allowed meter denominators (spec/score_format.md section 2). */
val DRAFT_DENOMINATORS: List<Int> = listOf(1, 2, 4, 8, 16, 32)

const val DRAFT_MIN_BPM: Double = 20.0
const val DRAFT_MAX_BPM: Double = 400.0
const val DEFAULT_DRAFT_BPM: String = "90"
private const val MAX_METER_NUM: Int = 32

/** Common meters offered as one-tap choices. */
val DRAFT_METER_PRESETS: List<DraftMeter> = listOf(DraftMeter(4, 4), DraftMeter(3, 4), DraftMeter(6, 8), DraftMeter(2, 4), DraftMeter(5, 4), DraftMeter(7, 8))

/**
 * The draft sheet's inputs as typed. [bpm] is required for a grid draft; blank for a recording
 * draft asks the server to estimate the tempo. [note] applies to grid drafts only.
 */
data class DraftForm(
    val source: DraftSource = DraftSource.GRID,
    val note: ScoreSnap = ScoreSnap.EIGHTH,
    val meterNum: String = "4",
    val meterDen: Int = 4,
    val bpm: String = DEFAULT_DRAFT_BPM,
    val recordingId: RecordingId? = null,
) {
    /**
     * Switch source. The grid's default tempo is dropped going to a recording (so it estimates)
     * and restored coming back to a blank grid tempo; a typed tempo is kept.
     */
    fun switchSource(next: DraftSource): DraftForm = when {
        next == source -> this
        next == DraftSource.RECORDING && bpm == DEFAULT_DRAFT_BPM -> copy(source = next, bpm = "")
        next == DraftSource.GRID && bpm.isBlank() -> copy(source = next, bpm = DEFAULT_DRAFT_BPM)
        else -> copy(source = next)
    }

    /** Keep the picked take while it is listed, else pick [defaultDraftRecording]. */
    fun withRecordings(recordings: List<Recording>): DraftForm =
        if (recordingId != null && recordings.any { it.id == recordingId }) this else copy(recordingId = defaultDraftRecording(recordings))

    /** The request to send, or the first problem (in field order). */
    fun check(recordings: List<Recording>): DraftFormCheck {
        if (source == DraftSource.RECORDING) {
            val take = recordings.firstOrNull { it.id == recordingId } ?: return DraftFormCheck.Invalid(DraftFormProblem.NO_RECORDING)
            if (!take.hasTimings) return DraftFormCheck.Invalid(DraftFormProblem.RECORDING_NO_TIMINGS)
        }
        val num = meterNum.trim().toIntOrNull()?.takeIf { it in 1..MAX_METER_NUM } ?: return DraftFormCheck.Invalid(DraftFormProblem.METER_NUM)
        if (meterDen !in DRAFT_DENOMINATORS) return DraftFormCheck.Invalid(DraftFormProblem.METER_DEN)
        val meter = DraftMeter(num, meterDen)
        val tempo = when (val parsed = parseBpm(bpm, required = source == DraftSource.GRID)) {
            is BpmParse.Problem -> return DraftFormCheck.Invalid(parsed.problem)
            is BpmParse.Value -> parsed.bpm
        }
        val request = when (source) {
            DraftSource.GRID -> ScoreDraftRequest.FromGrid(note, meter, requireNotNull(tempo))
            DraftSource.RECORDING -> ScoreDraftRequest.FromRecording(requireNotNull(recordingId), meter, tempo)
        }
        return DraftFormCheck.Ready(request)
    }
}

sealed interface DraftFormCheck {
    data class Ready(val request: ScoreDraftRequest) : DraftFormCheck

    data class Invalid(val problem: DraftFormProblem) : DraftFormCheck
}

enum class DraftFormProblem(val message: String) {
    NO_RECORDING("Pick a recording"),
    RECORDING_NO_TIMINGS("This take has no word timings"),
    METER_NUM("Beats per bar must be 1 to 32"),
    METER_DEN("Beat note must be 1, 2, 4, 8, 16 or 32"),
    BPM_REQUIRED("Enter a tempo"),
    BPM_NOT_A_NUMBER("Tempo must be a number"),
    BPM_RANGE("Tempo must be 20 to 400 bpm"),
    BPM_DECIMALS("Tempo takes at most two decimals"),
}

private sealed interface BpmParse {
    data class Value(val bpm: Double?) : BpmParse

    data class Problem(val problem: DraftFormProblem) : BpmParse
}

/** A tempo in 20..400 with at most two decimals (spec section 14.4); "," is read as ".". Blank is null unless [required]. */
private fun parseBpm(text: String, required: Boolean): BpmParse {
    val trimmed = text.trim().replace(',', '.')
    if (trimmed.isEmpty()) return if (required) BpmParse.Problem(DraftFormProblem.BPM_REQUIRED) else BpmParse.Value(null)
    val bpm = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return BpmParse.Problem(DraftFormProblem.BPM_NOT_A_NUMBER)
    if (bpm < DRAFT_MIN_BPM || bpm > DRAFT_MAX_BPM) return BpmParse.Problem(DraftFormProblem.BPM_RANGE)
    val hundredths = bpm * 100
    if (abs(hundredths - hundredths.roundToLong()) > 1e-6) return BpmParse.Problem(DraftFormProblem.BPM_DECIMALS)
    return BpmParse.Value(bpm)
}

/** The newest take with word timings (the server lists newest first), or null. */
fun defaultDraftRecording(recordings: List<Recording>): RecordingId? = recordings.firstOrNull { it.hasTimings }?.id

/** "2026-11-01 10:00 · 0:04", plus " · no word timings" for a take that cannot be drafted. */
fun takeLabel(recording: Recording): String {
    val stamp = recording.createdAt.take(16).replace('T', ' ')
    val seconds = (recording.durationMs + 500) / 1000
    val length = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    val base = "$stamp · $length"
    return if (recording.hasTimings) base else "$base · no word timings"
}
