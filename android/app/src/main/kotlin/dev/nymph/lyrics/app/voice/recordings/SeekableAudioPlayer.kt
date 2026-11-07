package dev.nymph.lyrics.app.voice.recordings

/**
 * An [AudioPlayer] whose position can be read and moved (the Check transcript player). Position
 * calls on an idle player read 0 and do nothing.
 */
interface SeekableAudioPlayer : AudioPlayer {
    /** Milliseconds into the file that is playing or paused; 0 when idle. */
    fun positionMs(): Long

    /** Move the playing or paused file to [positionMs]; no-op when idle. */
    fun seekTo(positionMs: Long)
}
