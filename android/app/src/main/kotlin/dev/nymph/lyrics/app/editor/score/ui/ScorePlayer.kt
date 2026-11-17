package dev.nymph.lyrics.app.editor.score.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.nymph.lyrics.logic.score.play.SynthVoice
import dev.nymph.lyrics.logic.score.play.playbackPlan
import dev.nymph.lyrics.logic.score.play.synthesize
import dev.nymph.lyrics.model.score.Score

/** Why playback could not start. */
enum class PlaybackError { AUDIO_UNAVAILABLE }

/**
 * Plays a score through a static [AudioTrack]: the whole performance (clicks on beats, louder on
 * downbeats, a short tone per note with gain by accent) is synthesized up front by
 * [synthesize] (no new dependencies). [positionMs] drives the playhead. One track at a time; all
 * calls come from the main thread.
 */
class ScorePlayer {
    private var track: AudioTrack? = null
    private var lengthMs: Double = 0.0

    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Milliseconds played, or null when stopped or finished. */
    fun positionMs(): Double? {
        val t = track ?: return null
        val ms = t.playbackHeadPosition * 1000.0 / SynthVoice.SAMPLE_RATE
        return if (ms >= lengthMs || t.playState != AudioTrack.PLAYSTATE_PLAYING) null else ms
    }

    /** Start [score] from tick 0 (stopping any playback first). */
    fun play(score: Score, metronome: Boolean = true): PlaybackError? {
        stop()
        val plan = playbackPlan(score)
        val pcm = synthesize(plan, SynthVoice.SAMPLE_RATE, metronome)
        lengthMs = pcm.size * 1000.0 / SynthVoice.SAMPLE_RATE
        val built = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SynthVoice.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "event=score.play_failed reason=unsupported message=${e.message}")
            return PlaybackError.AUDIO_UNAVAILABLE
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "event=score.play_failed reason=bad_format message=${e.message}")
            return PlaybackError.AUDIO_UNAVAILABLE
        }
        if (built.write(pcm, 0, pcm.size) < 0 || built.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "event=score.play_failed reason=write state=${built.state}")
            built.release()
            return PlaybackError.AUDIO_UNAVAILABLE
        }
        built.play()
        track = built
        Log.d(TAG, "event=score.play ms=${lengthMs.toLong()} notes=${plan.notes.size} clicks=${plan.clicks.size}")
        return null
    }

    fun stop() {
        track?.let { t ->
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.release()
        }
        track = null
    }

    private companion object {
        const val TAG = "Score"
    }
}
