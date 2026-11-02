package dev.nymph.lyrics.logic.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Auto-stop after this much continuous silence; [OFF] never stops. The modal's choices. */
enum class SilenceTimeout(val seconds: Int) {
    OFF(0),
    S2(2),
    S3(3),
    S5(5),
    S8(8),
    S10(10),
    ;

    val millis: Long get() = seconds * 1_000L

    companion object {
        val Default: SilenceTimeout = OFF

        /** A persisted value, or null when it is not one of the choices. */
        fun fromSeconds(seconds: Int): SilenceTimeout? = entries.firstOrNull { it.seconds == seconds }
    }
}

/** Full scale of `MediaRecorder.getMaxAmplitude()` (16-bit PCM). */
const val MAX_AMPLITUDE: Int = 32_767

/** The quietest level the VAD and the meter distinguish. */
const val MIN_DBFS: Double = -90.0

/** A peak amplitude (0..[MAX_AMPLITUDE]) in dB relative to full scale, never below [MIN_DBFS]. */
fun amplitudeToDbfs(amplitude: Int): Double {
    if (amplitude <= 0) return MIN_DBFS
    val ratio = min(amplitude, MAX_AMPLITUDE).toDouble() / MAX_AMPLITUDE
    return max(20 * log10(ratio), MIN_DBFS)
}

/** The level meter's fill (0..1): -60 dBFS and below is empty, full scale is full. */
fun levelFraction(amplitude: Int, floorDb: Double = -60.0): Float =
    ((amplitudeToDbfs(amplitude) - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()

/**
 * Tuning of [VadState]. Levels are dBFS of the per-poll peak amplitude.
 *
 * @param speechMarginDb a sample this far above the noise floor is speech
 * @param silenceMarginDb a sample less than this above the noise floor is silence
 * @param armAfterSpeechMs speech must last this long (one run) before silence can stop the take
 * @param blipToleranceMs a non-silent run shorter than this (a click, a breath) does not end a silence run
 * @param floorFallFactor share of the gap the floor moves down per sample when the level is below it
 * @param floorRiseDbPerSec how fast the floor follows a louder background (never during speech)
 * @param minFloorDb the floor never goes below this, so a dead-quiet input still has a usable threshold
 */
data class VadConfig(
    val speechMarginDb: Double = 15.0,
    val silenceMarginDb: Double = 9.0,
    val armAfterSpeechMs: Long = 150,
    val blipToleranceMs: Long = 200,
    val floorFallFactor: Double = 0.3,
    val floorRiseDbPerSec: Double = 2.0,
    val minFloorDb: Double = -65.0,
) {
    companion object {
        val Default: VadConfig = VadConfig()
    }
}

/**
 * Energy voice-activity detector for silence auto-stop, fed one peak amplitude per poll.
 *
 * The noise floor adapts: it falls toward quieter samples quickly and rises slowly toward a
 * louder background, but never while someone is speaking. Silence can only stop the take once
 * it is [armed] (speech was heard once); it stops after [SilenceTimeout] of continuous silence,
 * where a short non-silent blip does not break the run but speech does. Only built from
 * [Initial] through [sample].
 */
@ConsistentCopyVisibility
data class VadState private constructor(
    /** Null before the first sample. */
    val floorDb: Double?,
    val lastSampleMs: Long?,
    /** Start of the current speech run. */
    val speechSinceMs: Long?,
    val armed: Boolean,
    /** Start of the current silence run. */
    val silenceSinceMs: Long?,
    /** Start of a non-silent, non-speech run inside a silence run. */
    val blipSinceMs: Long?,
) {
    /** Add the peak [amplitude] polled at [atMs]; [VadStep.stop] when the take should end. */
    fun sample(atMs: Long, amplitude: Int, timeout: SilenceTimeout, config: VadConfig = VadConfig.Default): VadStep {
        val level = amplitudeToDbfs(amplitude)
        val floor = floorDb
        if (floor == null) {
            val first = VadState(max(level, config.minFloorDb), atMs, null, false, null, null)
            return VadStep(first, stop = false)
        }
        val dtMs = (atMs - (lastSampleMs ?: atMs)).coerceAtLeast(0)
        val speech = level >= floor + config.speechMarginDb
        val silent = level < floor + config.silenceMarginDb

        val speechSince = if (speech) speechSinceMs ?: atMs else null
        val armedNow = armed || (speechSince != null && atMs - speechSince >= config.armAfterSpeechMs)

        var silenceSince = silenceSinceMs
        var blipSince: Long? = null
        when {
            silent -> silenceSince = silenceSince ?: atMs
            speech -> silenceSince = null
            silenceSince != null -> {
                blipSince = blipSinceMs ?: atMs
                if (atMs - blipSince >= config.blipToleranceMs) {
                    silenceSince = null
                    blipSince = null
                }
            }
        }

        val nextFloor = when {
            level < floor -> max(floor + (level - floor) * config.floorFallFactor, config.minFloorDb)
            speech -> floor
            else -> min(floor + config.floorRiseDbPerSec * dtMs / 1_000.0, level)
        }

        val next = VadState(nextFloor, atMs, speechSince, armedNow, silenceSince, blipSince)
        val stop = timeout != SilenceTimeout.OFF && armedNow && silenceSince != null && atMs - silenceSince >= timeout.millis
        return VadStep(next, stop)
    }

    companion object {
        val Initial: VadState = VadState(null, null, null, false, null, null)
    }
}

/** The result of one VAD sample. */
data class VadStep(val state: VadState, val stop: Boolean)
