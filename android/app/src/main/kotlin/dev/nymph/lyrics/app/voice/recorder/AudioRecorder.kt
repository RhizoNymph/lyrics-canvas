package dev.nymph.lyrics.app.voice.recorder

import dev.nymph.lyrics.logic.voice.AudioFile
import dev.nymph.lyrics.logic.voice.RecorderFailure

/** Outcome of [AudioRecorder.start]. */
sealed interface RecorderStart {
    data class Started(val audio: AudioFile) : RecorderStart

    data class Failed(val failure: RecorderFailure) : RecorderStart
}

/**
 * The microphone recorder behind the voice modal, one take at a time. Called on the main thread.
 * Implementations never throw: platform exceptions become [RecorderStart.Failed] / `false`.
 */
interface AudioRecorder {
    /** Start a new take into a new file (a running one is aborted first). */
    fun start(): RecorderStart

    /** Peak amplitude (0..32767) since the previous call; 0 when not recording. */
    fun maxAmplitude(): Int

    /** Stop and release, keeping the file. False when the file is unusable (no audio captured). */
    fun finish(): Boolean

    /** Stop and release, ignoring errors; the caller deletes the file. No-op when idle. */
    fun abort()
}
