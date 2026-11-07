package dev.nymph.lyrics.app.voice.check

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.voice.recordings.RecordingUpdates
import dev.nymph.lyrics.app.voice.recordings.SeekableAudioPlayer
import dev.nymph.lyrics.app.voice.recordings.ensureRecordingAudio
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.logic.voice.TranscriptReference
import dev.nymph.lyrics.logic.voice.VerificationInput
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.logic.voice.initialCorrectionText
import dev.nymph.lyrics.logic.voice.toVoiceUploadError
import dev.nymph.lyrics.logic.voice.transcriptReference
import dev.nymph.lyrics.logic.voice.validateVerification
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

/** How often the player position is read while playing (scrubber, time, word highlight). */
const val CHECK_POSITION_POLL_MS: Long = 100

/**
 * The Check transcript screen: loads the recording (from its snippet's list, so the screen
 * survives process death with only ids in the route), fetches its audio into [cacheDir], plays it
 * with [player], and saves or clears the checked transcript. Saved and cleared recordings are
 * published on [updates] for the editor's list. Called on the main thread; nothing throws.
 */
class CheckTranscriptViewModel(
    private val snippetId: SnippetId,
    private val recordingId: RecordingId,
    private val voice: VoiceRepository,
    private val cacheDir: File,
    private val player: SeekableAudioPlayer,
    private val updates: RecordingUpdates,
    private val positionPollMs: Long = CHECK_POSITION_POLL_MS,
) : ViewModel() {
    private val _state = MutableStateFlow<CheckTranscriptUiState>(CheckTranscriptUiState.Loading)
    val state: StateFlow<CheckTranscriptUiState> = _state.asStateFlow()

    private val _events = Channel<CheckTranscriptEvent>(Channel.BUFFERED)
    val events: Flow<CheckTranscriptEvent> = _events.receiveAsFlow()

    /** The cached audio once fetched. */
    private var audio: File? = null

    /** True while [player] holds [audio] (playing or paused); false before the first play and after the end. */
    private var loaded = false
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        load()
    }

    fun retryLoad() {
        if (_state.value is CheckTranscriptUiState.LoadFailed) load()
    }

    private fun load() {
        loadJob?.cancel()
        _state.value = CheckTranscriptUiState.Loading
        loadJob = viewModelScope.launch {
            val recording = when (val result = voice.recordings(snippetId)) {
                is ApiResult.Failure -> {
                    _state.value = CheckTranscriptUiState.LoadFailed(toVoiceUploadError(result.error))
                    return@launch
                }
                is ApiResult.Success -> result.value.firstOrNull { it.id == recordingId }
            }
            if (recording == null) {
                _state.value = CheckTranscriptUiState.LoadFailed(VoiceUploadError.Rejected(VoiceErrorCode.RECORDING_NOT_FOUND, 404, null))
                return@launch
            }
            val text = initialCorrectionText(recording)
            _state.value = CheckTranscriptUiState.Ready(
                recording = recording,
                reference = transcriptReference(recording),
                text = text,
                baseline = text,
                player = PlayerUi.Loading,
            )
            val player = when (val fetched = ensureRecordingAudio(voice, cacheDir, recording)) {
                is ApiResult.Success -> {
                    audio = fetched.value
                    PlayerUi.Ready(positionMs = 0, durationMs = recording.durationMs.coerceAtLeast(0), playing = false)
                }
                is ApiResult.Failure -> PlayerUi.Failed(PlayerFailure.Download(toVoiceUploadError(fetched.error)))
            }
            updateReady { it.copy(player = player) }
        }
    }

    // ---- correction ------------------------------------------------------------------------

    fun editText(text: String) = updateReady { it.copy(text = text, error = null) }

    /** Validate, then PUT; success publishes the recording and closes. */
    fun save() {
        val s = ready() ?: return
        if (s.busy) return
        when (val input = validateVerification(s.text)) {
            is VerificationInput.Invalid -> updateReady { it.copy(error = CheckError.Input(input.error)) }
            is VerificationInput.Valid -> {
                updateReady { it.copy(action = CheckAction.SAVING, error = null) }
                viewModelScope.launch {
                    when (val result = voice.saveVerification(recordingId, s.text)) {
                        is ApiResult.Success -> {
                            updateReady { it.copy(recording = result.value, action = CheckAction.IDLE) }
                            updates.publish(result.value)
                            _events.trySend(CheckTranscriptEvent.Close)
                        }
                        is ApiResult.Failure -> updateReady {
                            it.copy(action = CheckAction.IDLE, error = CheckError.Server(toVoiceUploadError(result.error)))
                        }
                    }
                }
            }
        }
    }

    /** DELETE the verification; the screen stays open with the field as it is. */
    fun markUnchecked() {
        val s = ready() ?: return
        if (s.busy || !s.checked) return
        updateReady { it.copy(action = CheckAction.CLEARING, error = null) }
        viewModelScope.launch {
            when (val result = voice.clearVerification(recordingId)) {
                is ApiResult.Success -> {
                    updateReady { it.copy(recording = result.value, action = CheckAction.IDLE) }
                    updates.publish(result.value)
                }
                is ApiResult.Failure -> updateReady {
                    it.copy(action = CheckAction.IDLE, error = CheckError.Server(toVoiceUploadError(result.error)))
                }
            }
        }
    }

    /** Close, after a confirmation when the field has edits. */
    fun cancel() {
        val s = ready()
        if (s != null && s.dirty) {
            updateReady { it.copy(confirmDiscard = true) }
        } else {
            close()
        }
    }

    fun confirmDiscard() = close()

    fun keepEditing() = updateReady { it.copy(confirmDiscard = false) }

    private fun close() {
        updateReady { it.copy(confirmDiscard = false) }
        pause()
        _events.trySend(CheckTranscriptEvent.Close)
    }

    // ---- player ----------------------------------------------------------------------------

    fun togglePlay() {
        val p = readyPlayer() ?: return
        when {
            p.playing -> pause()
            loaded -> {
                player.resume()
                setPlaying(true)
            }
            else -> start(p.positionMs)
        }
    }

    /** Pause if playing (screen stopped, or Cancel). */
    fun pause() {
        val p = readyPlayer() ?: return
        if (!p.playing) return
        player.pause()
        setPlaying(false)
    }

    fun seekTo(positionMs: Long) {
        val p = readyPlayer() ?: return
        val target = positionMs.coerceIn(0, p.durationMs)
        if (loaded) player.seekTo(target)
        setPlayer(p.copy(positionMs = target))
    }

    /** A tapped Whisper word: seek to its start, and play when not playing. Needs timings. */
    fun seekToWord(index: Int) {
        val s = ready() ?: return
        val timed = s.reference as? TranscriptReference.Timed ?: return
        val word = timed.words.getOrNull(index) ?: return
        val p = readyPlayer() ?: return
        seekTo(word.startMs)
        if (!p.playing) togglePlay()
    }

    private fun start(fromMs: Long) {
        val file = audio ?: return
        val started = player.play(file, ::onComplete)
        if (!started) {
            file.delete()
            audio = null
            updateReady { it.copy(player = PlayerUi.Failed(PlayerFailure.Unplayable)) }
            return
        }
        loaded = true
        if (fromMs > 0) player.seekTo(fromMs)
        setPlaying(true)
    }

    private fun onComplete() {
        loaded = false
        val p = readyPlayer() ?: return
        stopPolling()
        setPlayer(p.copy(positionMs = 0, playing = false))
    }

    private fun setPlaying(playing: Boolean) {
        val p = readyPlayer() ?: return
        setPlayer(p.copy(playing = playing))
        if (playing) startPolling() else stopPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(positionPollMs)
                val p = readyPlayer() ?: break
                if (!p.playing) break
                setPlayer(p.copy(positionMs = player.positionMs().coerceIn(0, p.durationMs)))
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun ready(): CheckTranscriptUiState.Ready? = _state.value as? CheckTranscriptUiState.Ready

    private fun readyPlayer(): PlayerUi.Ready? = ready()?.player as? PlayerUi.Ready

    private fun setPlayer(p: PlayerUi.Ready) = updateReady { it.copy(player = p) }

    private inline fun updateReady(change: (CheckTranscriptUiState.Ready) -> CheckTranscriptUiState.Ready) {
        val s = _state.value as? CheckTranscriptUiState.Ready ?: return
        _state.value = change(s)
    }

    override fun onCleared() {
        stopPolling()
        player.stop()
    }
}

