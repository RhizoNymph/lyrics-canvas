package dev.nymph.lyrics.app.voice.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dev.nymph.lyrics.logic.voice.AudioFile
import dev.nymph.lyrics.logic.voice.RecorderFailure
import dev.nymph.lyrics.model.voice.AudioMime
import java.io.File
import java.io.IOException

private const val TAG = "VoiceRecorder"

/**
 * [AudioRecorder] on [MediaRecorder]: mono OGG / Opus on API 29+ (`audio/ogg`), AAC in MPEG-4
 * below (`audio/mp4`), written to [dir] (the app cache). One MediaRecorder per take, released on
 * finish / abort. MediaRecorder signals failures with runtime exceptions (no checked ones), which
 * are caught here by their documented types and turned into typed results.
 */
class MediaRecorderAudioRecorder(private val context: Context, private val dir: File) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(): RecorderStart {
        abort()
        val mime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) AudioMime.OGG else AudioMime.MP4
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "event=recorder_start_failed reason=no_dir dir=${dir.path}")
            return RecorderStart.Failed(RecorderFailure.START_FAILED)
        }
        val file = File(dir, "take-${System.currentTimeMillis()}.${mime.extension}")
        val created = newRecorder()
        return try {
            created.configure(mime, file)
            created.prepare()
            created.start()
            recorder = created
            Log.d(TAG, "event=recorder_started mime=${mime.wire} file=${file.name}")
            RecorderStart.Started(AudioFile(file.absolutePath, mime))
        } catch (e: IOException) {
            failStart(created, file, e)
        } catch (e: IllegalStateException) {
            failStart(created, file, e)
        } catch (e: RuntimeException) {
            // setAudioSource / start throw a plain RuntimeException when the microphone is unavailable.
            failStart(created, file, e)
        }
    }

    override fun maxAmplitude(): Int {
        val active = recorder ?: return 0
        return try {
            active.maxAmplitude
        } catch (e: IllegalStateException) {
            0
        }
    }

    override fun finish(): Boolean {
        val active = recorder ?: return false
        recorder = null
        return try {
            active.stop()
            true
        } catch (e: RuntimeException) {
            // stop() throws when no valid audio was captured (stopped right after start).
            Log.w(TAG, "event=recorder_finish_failed error=${e.javaClass.simpleName} message=${e.message}")
            false
        } finally {
            active.release()
        }
    }

    override fun abort() {
        val active = recorder ?: return
        recorder = null
        try {
            active.stop()
        } catch (e: RuntimeException) {
            Log.d(TAG, "event=recorder_abort_stop_ignored error=${e.javaClass.simpleName}")
        } finally {
            active.release()
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    private fun MediaRecorder.configure(mime: AudioMime, file: File) {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        when (mime) {
            AudioMime.OGG -> {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(48_000)
            }
            AudioMime.MP4 -> {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
            }
        }
        setAudioChannels(1)
        setOutputFile(file.absolutePath)
    }

    private fun failStart(created: MediaRecorder, file: File, e: Exception): RecorderStart {
        Log.w(TAG, "event=recorder_start_failed error=${e.javaClass.simpleName} message=${e.message}")
        created.release()
        file.delete()
        return RecorderStart.Failed(RecorderFailure.START_FAILED)
    }
}
