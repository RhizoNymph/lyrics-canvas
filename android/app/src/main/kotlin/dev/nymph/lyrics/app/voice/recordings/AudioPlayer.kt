package dev.nymph.lyrics.app.voice.recordings

import java.io.File

/**
 * Plays one audio file at a time. Called on the main thread; never throws (a file the platform
 * cannot play makes [play] return false).
 */
interface AudioPlayer {
    /** Play [file] from the start, replacing anything playing; [onComplete] runs when it ends. */
    fun play(file: File, onComplete: () -> Unit): Boolean

    fun pause()

    fun resume()

    /** Stop and release; no-op when idle. */
    fun stop()
}
