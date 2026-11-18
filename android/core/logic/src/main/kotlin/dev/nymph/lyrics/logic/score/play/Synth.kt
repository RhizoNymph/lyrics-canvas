package dev.nymph.lyrics.logic.score.play

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Sound of the synthesized playback (no samples, no dependencies: sine bursts). */
object SynthVoice {
    const val SAMPLE_RATE: Int = 22_050
    const val TONE_HZ: Double = 660.0
    const val CLICK_HZ: Double = 1_000.0
    const val DOWNBEAT_HZ: Double = 1_500.0
    const val CLICK_MS: Double = 30.0
    const val CLICK_GAIN: Float = 0.35f
    const val DOWNBEAT_GAIN: Float = 0.6f
    /** Notes are cut a little before the next one so repeated syllables stay distinct. */
    const val NOTE_GAP_MS: Double = 12.0
    const val ATTACK_MS: Double = 5.0
    /** Longest playback rendered (a guard against enormous scores at slow tempos). */
    const val MAX_MS: Double = 10 * 60 * 1000.0
    /** Headroom so a click on top of a strong note does not clip. */
    const val MASTER: Float = 0.45f
}

/** Mono 16-bit PCM of [plan] at [sampleRate] (clicks unless [metronome] is off, plus every note). */
fun synthesize(plan: PlaybackPlan, sampleRate: Int = SynthVoice.SAMPLE_RATE, metronome: Boolean = true): ShortArray {
    val totalMs = minOf(plan.durationMs, SynthVoice.MAX_MS)
    val length = ((totalMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(1)
    val mix = FloatArray(length)
    fun index(ms: Double) = ((ms / 1000.0) * sampleRate).roundToInt()

    fun burst(startMs: Double, durationMs: Double, hz: Double, gain: Float, decayMs: Double) {
        val from = index(startMs)
        val count = index(durationMs).coerceAtLeast(1)
        val attack = index(SynthVoice.ATTACK_MS).coerceAtLeast(1)
        for (i in 0 until count) {
            val at = from + i
            if (at !in mix.indices) break
            val envelope = minOf(1.0, i.toDouble() / attack) * exp(-i.toDouble() / index(decayMs).coerceAtLeast(1))
            mix[at] += (gain * envelope * sin(2 * PI * hz * i / sampleRate)).toFloat()
        }
    }

    if (metronome) {
        for (c in plan.clicks) {
            burst(c.ms, SynthVoice.CLICK_MS, if (c.downbeat) SynthVoice.DOWNBEAT_HZ else SynthVoice.CLICK_HZ, if (c.downbeat) SynthVoice.DOWNBEAT_GAIN else SynthVoice.CLICK_GAIN, SynthVoice.CLICK_MS / 3)
        }
    }
    for (n in plan.notes) {
        val duration = (n.durationMs - SynthVoice.NOTE_GAP_MS).coerceAtLeast(SynthVoice.ATTACK_MS * 2)
        burst(n.startMs, duration, SynthVoice.TONE_HZ, n.gain, duration * 0.8)
    }
    return ShortArray(length) { i -> (mix[i] * SynthVoice.MASTER).coerceIn(-1f, 1f).times(Short.MAX_VALUE).toInt().toShort() }
}
