package dev.nymph.lyrics.app.voice.recordings

import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.Recording
import java.io.File

/** Where a recording's audio is cached: `<cacheDir>/recording-<id>.<ext>`. */
fun recordingCacheFile(cacheDir: File, recording: Recording): File {
    val extension = AudioMime.parse(recording.mimeType)?.extension ?: "audio"
    return File(cacheDir, "recording-${recording.id.value}.$extension")
}

/** The cached audio of [recording], downloaded (with the bearer token) when it is not there yet. */
suspend fun ensureRecordingAudio(voice: VoiceRepository, cacheDir: File, recording: Recording): ApiResult<File> {
    val file = recordingCacheFile(cacheDir, recording)
    return if (file.isFile && file.length() > 0) ApiResult.Success(file) else voice.downloadAudio(recording.id, file)
}
