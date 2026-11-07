package dev.nymph.lyrics.app.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.voice.recorder.AudioRecorder
import dev.nymph.lyrics.app.voice.recorder.RecorderStart
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.logic.voice.AudioFile
import dev.nymph.lyrics.logic.voice.CapturedTake
import dev.nymph.lyrics.logic.voice.RecorderEffect
import dev.nymph.lyrics.logic.voice.RecorderEvent
import dev.nymph.lyrics.logic.voice.RecorderState
import dev.nymph.lyrics.logic.voice.SilenceTimeout
import dev.nymph.lyrics.logic.voice.reduceRecorder
import dev.nymph.lyrics.logic.voice.toVoiceUploadError
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** How often the recorder's peak amplitude is polled (meter, silence detection, tap window). */
const val VOICE_SAMPLE_INTERVAL_MS: Long = 50

/** A take the server stored, for the screen that opened the modal to apply. */
data class VoiceTakeSaved(val target: VoiceTakeTarget, val response: VoiceTakeResponse)

/**
 * The voice recording modal: interprets [reduceRecorder] (the pure state machine) against the
 * device recorder, the clock and the voice API. All calls come from the main thread; the
 * recorder is driven there too, and the upload runs in [viewModelScope].
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime` on the device); taps,
 * samples and the recorder start are all read from it, so breaks are ms since the recorder started
 * @param deleteFile removes a take's file (the machine says when)
 */
class VoiceCaptureViewModel(
    private val canvasId: CanvasId,
    private val voice: VoiceRepository,
    private val recorder: AudioRecorder,
    private val settings: VoiceSettingsStore,
    private val clock: () -> Long,
    private val deleteFile: (AudioFile) -> Unit,
    private val sampleIntervalMs: Long = VOICE_SAMPLE_INTERVAL_MS,
) : ViewModel() {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** The persisted silence auto-stop choice. */
    val silence: StateFlow<SilenceTimeout> = settings.silence.stateIn(viewModelScope, SharingStarted.Eagerly, SilenceTimeout.Default)

    /** The persisted "Use word bank as hints" choice, sent with every upload. */
    val vocabularyHints: StateFlow<Boolean> = settings.vocabularyHints.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _saved = Channel<VoiceTakeSaved>(Channel.BUFFERED)

    /** One element per stored take, for the host screen. */
    val saved: Flow<VoiceTakeSaved> = _saved.receiveAsFlow()

    private var samplingJob: Job? = null
    private var uploadJob: Job? = null

    /** Open the modal for [target]; ignored while a take is already in progress. */
    fun open(target: VoiceTakeTarget) = dispatch(RecorderEvent.Open(target))

    fun permissionGranted() = dispatch(RecorderEvent.PermissionGranted)

    fun permissionRationaleNeeded() = dispatch(RecorderEvent.PermissionRationaleNeeded)

    fun permissionDenied(permanently: Boolean) = dispatch(RecorderEvent.PermissionDenied(permanently))

    fun requestPermissionAgain() = dispatch(RecorderEvent.RequestPermissionAgain)

    /** A pointer went down on the modal's tap surface (read the clock now: the break is at the down). */
    fun tap() = dispatch(RecorderEvent.Tap(clock()))

    fun stop() = dispatch(RecorderEvent.Stop(clock()))

    fun cancel() = dispatch(RecorderEvent.Cancel)

    fun retry() = dispatch(RecorderEvent.Retry)

    fun discard() = dispatch(RecorderEvent.Discard)

    /** The host left the foreground: a running take is finished and held for the user. */
    fun hostStopped() = dispatch(RecorderEvent.Interrupt(clock()))

    /** Persist the auto-stop choice; a running take uses it at once. */
    fun setSilence(value: SilenceTimeout) {
        viewModelScope.launch { settings.setSilence(value) }
        dispatch(RecorderEvent.SetSilence(value))
    }

    /** Persist the hints choice; the next upload (or retry) sends it. */
    fun setVocabularyHints(enabled: Boolean) {
        viewModelScope.launch { settings.setVocabularyHints(enabled) }
    }

    private fun dispatch(event: RecorderEvent) {
        val transition = reduceRecorder(_state.value, event)
        _state.value = transition.state
        transition.effects.forEach(::perform)
        val done = _state.value
        if (done is RecorderState.Done && done === transition.state) {
            _saved.trySend(VoiceTakeSaved(done.target, done.response))
            dispatch(RecorderEvent.Acknowledge)
        }
    }

    private fun perform(effect: RecorderEffect) {
        when (effect) {
            RecorderEffect.StartRecorder -> when (val started = recorder.start()) {
                is RecorderStart.Started -> {
                    dispatch(RecorderEvent.RecorderStarted(started.audio, clock(), silence.value))
                    startSampling()
                }
                is RecorderStart.Failed -> dispatch(RecorderEvent.RecorderStartFailed(started.failure))
            }
            RecorderEffect.FinishRecorder -> {
                stopSampling()
                dispatch(if (recorder.finish()) RecorderEvent.RecorderFinished else RecorderEvent.RecorderFinishFailed)
            }
            RecorderEffect.AbortRecorder -> {
                stopSampling()
                recorder.abort()
            }
            is RecorderEffect.DeleteFile -> deleteFile(effect.audio)
            is RecorderEffect.Upload -> upload(effect.target, effect.take)
            RecorderEffect.CancelUpload -> {
                uploadJob?.cancel()
                uploadJob = null
            }
        }
    }

    private fun startSampling() {
        samplingJob?.cancel()
        samplingJob = viewModelScope.launch {
            while (_state.value is RecorderState.Recording) {
                delay(sampleIntervalMs)
                if (_state.value !is RecorderState.Recording) break
                dispatch(RecorderEvent.Sample(clock(), recorder.maxAmplitude()))
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }

    private fun upload(target: VoiceTakeTarget, take: CapturedTake) {
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            val result = voice.uploadTake(canvasId, File(take.audio.path), take.audio.mime, take.meta(target, vocabularyHints.value))
            ensureActive()
            dispatch(
                when (result) {
                    is ApiResult.Success -> RecorderEvent.UploadSucceeded(result.value)
                    is ApiResult.Failure -> RecorderEvent.UploadFailed(toVoiceUploadError(result.error))
                },
            )
        }
    }

    /** Leaving for good: release the microphone and drop any file still on disk. */
    override fun onCleared() {
        dispatch(RecorderEvent.Cancel)
    }
}
