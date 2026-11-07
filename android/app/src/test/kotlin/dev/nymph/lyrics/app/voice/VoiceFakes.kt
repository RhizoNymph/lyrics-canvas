package dev.nymph.lyrics.app.voice

import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.voice.recorder.AudioRecorder
import dev.nymph.lyrics.app.voice.recorder.RecorderStart
import dev.nymph.lyrics.app.voice.recordings.SeekableAudioPlayer
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.logic.voice.AudioFile
import dev.nymph.lyrics.logic.voice.RecorderFailure
import dev.nymph.lyrics.logic.voice.SilenceTimeout
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.RecordingStats
import dev.nymph.lyrics.model.voice.SplitMode
import dev.nymph.lyrics.model.voice.VoiceTakeMeta
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

val TAKE_AUDIO = AudioFile("/cache/voice/take-1.ogg", AudioMime.OGG)

fun recording(id: String, snippetId: String = "s1", createdAt: String = "2026-11-01T10:00:00Z", transcript: String = "hello") =
    Recording(RecordingId(id), SnippetId(snippetId), "audio/ogg", 2_000, 1_000, transcript, SplitMode.TAPS, createdAt)

fun takeResponse(snippet: Snippet = snippet("v1", rawText = "hello\nworld"), created: Boolean = true) = VoiceTakeResponse(
    snippet = snippet,
    recording = recording("r-${snippet.id.value}", snippet.id.value),
    lines = snippet.rawText.split("\n"),
    splitMode = SplitMode.TAPS,
    created = created,
)

class FakeAudioRecorder : AudioRecorder {
    var startResult: RecorderStart = RecorderStart.Started(TAKE_AUDIO)
    var finishResult = true
    var amplitude = 0
    var starts = 0
    var finishes = 0
    var aborts = 0
    var running = false

    override fun start(): RecorderStart {
        starts++
        running = startResult is RecorderStart.Started
        return startResult
    }

    override fun maxAmplitude(): Int = if (running) amplitude else 0

    override fun finish(): Boolean {
        finishes++
        running = false
        return finishResult
    }

    override fun abort() {
        aborts++
        running = false
    }

    companion object {
        val FAILED = RecorderStart.Failed(RecorderFailure.START_FAILED)
    }
}

data class Upload(val canvasId: CanvasId, val path: String, val mime: AudioMime, val meta: VoiceTakeMeta)

/** Uploads wait on [gate] when set, so a test can cancel mid-flight. */
class FakeVoiceRepository : VoiceRepository {
    val uploads = mutableListOf<Upload>()
    var uploadResult: (Upload) -> ApiResult<VoiceTakeResponse> = { ApiResult.Success(takeResponse()) }
    var gate: CompletableDeferred<Unit>? = null
    var completedUploads = 0

    var list: ApiResult<List<Recording>> = ApiResult.Success(emptyList())
    var listCalls = 0
    val downloads = mutableListOf<Pair<RecordingId, File>>()
    var downloadResult: (RecordingId, File) -> ApiResult<File> = { _, f -> ApiResult.Success(f) }
    var downloadGate: CompletableDeferred<Unit>? = null
    val deletes = mutableListOf<RecordingId>()
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun uploadTake(canvasId: CanvasId, audio: File, mime: AudioMime, meta: VoiceTakeMeta): ApiResult<VoiceTakeResponse> {
        val upload = Upload(canvasId, audio.path, mime, meta)
        uploads += upload
        gate?.await()
        completedUploads++
        return uploadResult(upload)
    }

    override suspend fun recordings(snippetId: SnippetId): ApiResult<List<Recording>> {
        listCalls++
        return list
    }

    override suspend fun downloadAudio(recordingId: RecordingId, target: File): ApiResult<File> {
        downloads += recordingId to target
        downloadGate?.await()
        return downloadResult(recordingId, target)
    }

    override suspend fun deleteRecording(recordingId: RecordingId): ApiResult<Unit> {
        deletes += recordingId
        return deleteResult
    }

    val verifications = mutableListOf<Pair<RecordingId, String>>()
    var verifyResult: (RecordingId, String) -> ApiResult<Recording> = { id, text ->
        ApiResult.Success(recording(id.value).copy(verifiedText = text, verifiedAt = "2026-11-06T10:00:00Z"))
    }
    var verifyGate: CompletableDeferred<Unit>? = null
    val clears = mutableListOf<RecordingId>()
    var clearResult: (RecordingId) -> ApiResult<Recording> = { id -> ApiResult.Success(recording(id.value)) }
    var statsResult: ApiResult<RecordingStats> = ApiResult.Success(RecordingStats(0, 0, 0, 0, 0))
    var statsCalls = 0
    var statsGate: CompletableDeferred<Unit>? = null

    override suspend fun saveVerification(recordingId: RecordingId, text: String): ApiResult<Recording> {
        verifications += recordingId to text
        verifyGate?.await()
        return verifyResult(recordingId, text)
    }

    override suspend fun clearVerification(recordingId: RecordingId): ApiResult<Recording> {
        clears += recordingId
        return clearResult(recordingId)
    }

    override suspend fun stats(): ApiResult<RecordingStats> {
        statsCalls++
        statsGate?.await()
        return statsResult
    }

    companion object {
        val STT_DOWN: ApiResult<VoiceTakeResponse> =
            ApiResult.Failure(ApiError.Server(503, "STT down", "stt_unavailable"))
    }
}

class FakeVoiceSettings(initial: SilenceTimeout = SilenceTimeout.OFF, initialHints: Boolean = true) : VoiceSettingsStore {
    val value = MutableStateFlow(initial)
    override val silence: Flow<SilenceTimeout> = value

    override suspend fun setSilence(value: SilenceTimeout) {
        this.value.value = value
    }

    val hints = MutableStateFlow(initialHints)
    override val vocabularyHints: Flow<Boolean> = hints

    override suspend fun setVocabularyHints(enabled: Boolean) {
        hints.value = enabled
    }
}

/** A [SeekableAudioPlayer] that records calls; position is set by the test. */
class FakeSeekablePlayer : SeekableAudioPlayer {
    val played = mutableListOf<File>()
    var canPlay = true
    var playing = false
    var position = 0L
    val seeks = mutableListOf<Long>()
    var pauses = 0
    var resumes = 0
    var stops = 0
    var onComplete: (() -> Unit)? = null

    override fun play(file: File, onComplete: () -> Unit): Boolean {
        played += file
        this.onComplete = onComplete
        playing = canPlay
        position = 0
        return canPlay
    }

    override fun pause() {
        pauses++
        playing = false
    }

    override fun resume() {
        resumes++
        playing = true
    }

    override fun stop() {
        stops++
        playing = false
    }

    override fun positionMs(): Long = position

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        position = positionMs
    }

    /** The platform reports the end of the file. */
    fun complete() {
        playing = false
        onComplete?.invoke()
    }
}

/** For ViewModels that are never asked to play. */
typealias SilentPlayer = FakeSeekablePlayer
