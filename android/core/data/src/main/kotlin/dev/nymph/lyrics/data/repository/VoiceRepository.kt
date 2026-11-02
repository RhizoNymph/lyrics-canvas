package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.LyricsJson
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.VoiceTakeMeta
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * The voice-capture routes (docs/features/voice_capture.md). Failures carry the server's error
 * code in [dev.nymph.lyrics.model.error.ApiError.code]; map them with `toVoiceUploadError`.
 */
interface VoiceRepository {
    /** Upload a take: `audio` file part plus the `meta` JSON field. */
    suspend fun uploadTake(canvasId: CanvasId, audio: File, mime: AudioMime, meta: VoiceTakeMeta): ApiResult<VoiceTakeResponse>

    /** A snippet's recordings, newest first. */
    suspend fun recordings(snippetId: SnippetId): ApiResult<List<Recording>>

    /** Fetch a recording's audio into [target] (complete or absent afterwards). */
    suspend fun downloadAudio(recordingId: RecordingId, target: File): ApiResult<File>

    suspend fun deleteRecording(recordingId: RecordingId): ApiResult<Unit>
}

class HttpVoiceRepository(private val api: ApiClient) : VoiceRepository {
    override suspend fun uploadTake(canvasId: CanvasId, audio: File, mime: AudioMime, meta: VoiceTakeMeta): ApiResult<VoiceTakeResponse> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", "take.${mime.extension}", audio.asRequestBody(mime.wire.toMediaType()))
            .addFormDataPart("meta", LyricsJson.encodeToString(VoiceTakeMeta.serializer(), meta))
            .build()
        return api.sendMultipart(listOf("api", "canvases", canvasId.value, "voice-takes"), body, VoiceTakeResponse.serializer())
    }

    override suspend fun recordings(snippetId: SnippetId): ApiResult<List<Recording>> =
        api.get(listOf("api", "snippets", snippetId.value, "recordings"), ListSerializer(Recording.serializer()))

    override suspend fun downloadAudio(recordingId: RecordingId, target: File): ApiResult<File> =
        api.download(listOf("api", "recordings", recordingId.value, "audio"), target)

    override suspend fun deleteRecording(recordingId: RecordingId): ApiResult<Unit> =
        api.delete(listOf("api", "recordings", recordingId.value))
}
