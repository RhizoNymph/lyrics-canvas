package dev.nymph.lyrics.model.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One STT word of a take; times are ms from the start of the audio. */
@Serializable
data class TimedWord(
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val probability: Double? = null,
)

/** A checked transcript: lines separated by `\n`, saved at [verifiedAt] (server timestamp). */
data class Verification(val text: String, val verifiedAt: String)

/** Body of `PUT /api/recordings/{id}/verification`. */
@Serializable
data class VerificationRequest(val text: String)

/** `GET /api/recordings/stats`: the caller's recordings across all canvases. */
@Serializable
data class RecordingStats(
    val total: Int,
    val verified: Int,
    @SerialName("total_duration_ms") val totalDurationMs: Long,
    @SerialName("verified_duration_ms") val verifiedDurationMs: Long,
    @SerialName("with_timings") val withTimings: Int,
)
