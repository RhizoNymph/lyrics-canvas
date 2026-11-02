package dev.nymph.lyrics.app.voice.recordings

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "VoicePlayer"

/** [AudioPlayer] on [MediaPlayer]; one player per [play], released on [stop] or completion. */
class MediaAudioPlayer : AudioPlayer {
    private var player: MediaPlayer? = null

    override fun play(file: File, onComplete: () -> Unit): Boolean {
        stop()
        val created = MediaPlayer()
        return try {
            created.setDataSource(file.path)
            created.setOnCompletionListener { finished ->
                if (player === finished) {
                    player = null
                    finished.release()
                    onComplete()
                }
            }
            created.prepare()
            created.start()
            player = created
            true
        } catch (e: IOException) {
            failPlay(created, file, e)
        } catch (e: IllegalStateException) {
            failPlay(created, file, e)
        } catch (e: IllegalArgumentException) {
            failPlay(created, file, e)
        }
    }

    override fun pause() {
        val active = player ?: return
        try {
            if (active.isPlaying) active.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "event=player_pause_failed message=${e.message}")
        }
    }

    override fun resume() {
        val active = player ?: return
        try {
            active.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "event=player_resume_failed message=${e.message}")
        }
    }

    override fun stop() {
        val active = player ?: return
        player = null
        try {
            active.stop()
        } catch (e: IllegalStateException) {
            Log.d(TAG, "event=player_stop_ignored message=${e.message}")
        } finally {
            active.release()
        }
    }

    private fun failPlay(created: MediaPlayer, file: File, e: Exception): Boolean {
        Log.w(TAG, "event=player_start_failed file=${file.name} error=${e.javaClass.simpleName} message=${e.message}")
        created.release()
        return false
    }
}
