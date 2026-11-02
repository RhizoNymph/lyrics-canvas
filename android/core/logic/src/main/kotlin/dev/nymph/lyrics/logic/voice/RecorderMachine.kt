package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.VoiceTakeMeta
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget

/** A recorder output file on the device. */
data class AudioFile(val path: String, val mime: AudioMime)

/** A finished take, kept until it is uploaded or discarded. Breaks are ms since the recorder started. */
data class CapturedTake(val audio: AudioFile, val durationMs: Long, val breaksMs: List<Long>) {
    fun meta(target: VoiceTakeTarget): VoiceTakeMeta = VoiceTakeMeta.of(breaksMs, durationMs, target)
}

/** Where the microphone permission stands while [RecorderState.Permission] is shown. */
enum class PermissionStatus {
    /** The host should check, and ask the system if needed. */
    REQUESTING,

    /** The system wants an explanation before asking (again). */
    RATIONALE,

    /** Denied; asking again is possible. */
    DENIED,

    /** Denied for good ("don't ask again"): only the app settings can grant it. */
    PERMANENTLY_DENIED,
}

/** Device-side recorder failures. */
enum class RecorderFailure {
    /** The microphone could not be opened (busy, no input, prepare failed). */
    START_FAILED,

    /** The recorder produced no usable file (stopped too soon, input lost). */
    NO_AUDIO,
}

/** Why a finished take is waiting for the user instead of uploading. */
sealed interface HoldReason {
    data class UploadFailed(val error: VoiceUploadError) : HoldReason

    /** The app went to the background mid-take; the take so far was kept. */
    data object Interrupted : HoldReason
}

/** What happens once the recorder has finalised its file. */
enum class AfterFinish { UPLOAD, HOLD }

/**
 * The recording modal's state. Every non-idle state knows its [VoiceTakeTarget]; only states
 * that own a file on disk carry it, so a file is always reachable for deletion.
 */
sealed interface RecorderState {
    data object Idle : RecorderState

    sealed interface Active : RecorderState {
        val target: VoiceTakeTarget
    }

    /** [requestId] grows each time the host should (re)run the permission check / system dialog. */
    data class Permission(
        override val target: VoiceTakeTarget,
        val status: PermissionStatus,
        val requestId: Int,
    ) : Active

    /** Permission granted; the recorder is being started. */
    data class Starting(override val target: VoiceTakeTarget) : Active

    data class Recording(
        override val target: VoiceTakeTarget,
        val audio: AudioFile,
        /** Clock time the recorder started; breaks and elapsed are relative to it. */
        val startedAtMs: Long,
        val elapsedMs: Long,
        /** Input level meter, 0..1. */
        val level: Float,
        val taps: TapState,
        val vad: VadState,
        val silence: SilenceTimeout,
    ) : Active {
        val lineNumber: Int get() = taps.lineNumber
    }

    /** Stop requested; the recorder is finalising [take]'s file. */
    data class Finishing(override val target: VoiceTakeTarget, val take: CapturedTake, val then: AfterFinish) : Active

    data class Uploading(override val target: VoiceTakeTarget, val take: CapturedTake) : Active

    /** A take that did not upload (failure or interruption): Retry uploads it again, Discard deletes it. */
    data class Held(override val target: VoiceTakeTarget, val take: CapturedTake, val reason: HoldReason) : Active

    /** The recorder failed; no take exists. Retry starts over. */
    data class RecorderFailed(override val target: VoiceTakeTarget, val failure: RecorderFailure) : Active

    /** The server stored the take; the host applies [response] and acknowledges. */
    data class Done(override val target: VoiceTakeTarget, val response: VoiceTakeResponse) : Active
}

sealed interface RecorderEvent {
    data class Open(val target: VoiceTakeTarget) : RecorderEvent
    data object PermissionGranted : RecorderEvent

    /** Before asking: the system wants a rationale shown first. */
    data object PermissionRationaleNeeded : RecorderEvent
    data class PermissionDenied(val permanently: Boolean) : RecorderEvent

    /** The user asked to be asked again (rationale / denied screen). */
    data object RequestPermissionAgain : RecorderEvent

    /** The recorder is running since [atMs]; [silence] is the auto-stop setting at that moment. */
    data class RecorderStarted(val audio: AudioFile, val atMs: Long, val silence: SilenceTimeout) : RecorderEvent
    data class RecorderStartFailed(val failure: RecorderFailure) : RecorderEvent

    /** A pointer went down on the tap surface (not on a button). */
    data class Tap(val atMs: Long) : RecorderEvent

    /** One poll: the time and the peak amplitude since the previous poll. Also advances the tap window. */
    data class Sample(val atMs: Long, val amplitude: Int) : RecorderEvent
    /** The auto-stop setting changed (applies to a running take). */
    data class SetSilence(val silence: SilenceTimeout) : RecorderEvent

    /** The Stop button. */
    data class Stop(val atMs: Long) : RecorderEvent

    /** The host stopped (app in the background): finish and hold the take. */
    data class Interrupt(val atMs: Long) : RecorderEvent

    data object RecorderFinished : RecorderEvent
    data object RecorderFinishFailed : RecorderEvent
    data class UploadSucceeded(val response: VoiceTakeResponse) : RecorderEvent
    data class UploadFailed(val error: VoiceUploadError) : RecorderEvent
    data object Retry : RecorderEvent
    data object Discard : RecorderEvent
    data object Cancel : RecorderEvent

    /** The host applied [RecorderState.Done]. */
    data object Acknowledge : RecorderEvent
}

/** Work the host performs after a transition, in order. */
sealed interface RecorderEffect {
    data object StartRecorder : RecorderEffect

    /** Stop the recorder keeping its file; answer with RecorderFinished / RecorderFinishFailed. */
    data object FinishRecorder : RecorderEffect

    /** Stop and release the recorder; its file is junk. */
    data object AbortRecorder : RecorderEffect
    data class DeleteFile(val audio: AudioFile) : RecorderEffect
    data class Upload(val target: VoiceTakeTarget, val take: CapturedTake) : RecorderEffect
    data object CancelUpload : RecorderEffect
}

data class RecorderTransition(val state: RecorderState, val effects: List<RecorderEffect> = emptyList())

private fun RecorderState.stay() = RecorderTransition(this)

/**
 * The recording modal as a pure reducer: idle -> permission -> starting -> recording ->
 * finishing -> uploading -> done | held, Cancel from anywhere. Events that do not apply to the
 * current state change nothing, except a recorder that starts after the modal was cancelled,
 * which is aborted and its file deleted.
 */
fun reduceRecorder(state: RecorderState, event: RecorderEvent, vad: VadConfig = VadConfig.Default): RecorderTransition {
    if (event is RecorderEvent.Cancel) return cancel(state)
    if (event is RecorderEvent.RecorderStarted && state !is RecorderState.Starting) {
        return RecorderTransition(state, listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(event.audio)))
    }
    return when (state) {
        RecorderState.Idle -> when (event) {
            is RecorderEvent.Open -> RecorderTransition(RecorderState.Permission(event.target, PermissionStatus.REQUESTING, 1))
            else -> state.stay()
        }
        is RecorderState.Permission -> permission(state, event)
        is RecorderState.Starting -> when (event) {
            is RecorderEvent.RecorderStarted -> RecorderTransition(
                RecorderState.Recording(state.target, event.audio, event.atMs, 0, 0f, TapState.Initial, VadState.Initial, event.silence),
            )
            is RecorderEvent.RecorderStartFailed -> RecorderTransition(RecorderState.RecorderFailed(state.target, event.failure))
            else -> state.stay()
        }
        is RecorderState.Recording -> recording(state, event, vad)
        is RecorderState.Finishing -> when (event) {
            RecorderEvent.RecorderFinished -> when (state.then) {
                AfterFinish.UPLOAD -> upload(state.target, state.take)
                AfterFinish.HOLD -> RecorderTransition(RecorderState.Held(state.target, state.take, HoldReason.Interrupted))
            }
            RecorderEvent.RecorderFinishFailed -> RecorderTransition(
                RecorderState.RecorderFailed(state.target, RecorderFailure.NO_AUDIO),
                listOf(RecorderEffect.DeleteFile(state.take.audio)),
            )
            else -> state.stay()
        }
        is RecorderState.Uploading -> when (event) {
            is RecorderEvent.UploadSucceeded -> RecorderTransition(
                RecorderState.Done(state.target, event.response),
                listOf(RecorderEffect.DeleteFile(state.take.audio)),
            )
            is RecorderEvent.UploadFailed -> RecorderTransition(RecorderState.Held(state.target, state.take, HoldReason.UploadFailed(event.error)))
            else -> state.stay()
        }
        is RecorderState.Held -> when (event) {
            RecorderEvent.Retry -> upload(state.target, state.take)
            RecorderEvent.Discard -> RecorderTransition(RecorderState.Idle, listOf(RecorderEffect.DeleteFile(state.take.audio)))
            else -> state.stay()
        }
        is RecorderState.RecorderFailed -> when (event) {
            RecorderEvent.Retry -> RecorderTransition(RecorderState.Permission(state.target, PermissionStatus.REQUESTING, 1))
            RecorderEvent.Discard -> RecorderTransition(RecorderState.Idle)
            else -> state.stay()
        }
        is RecorderState.Done -> when (event) {
            RecorderEvent.Acknowledge -> RecorderTransition(RecorderState.Idle)
            else -> state.stay()
        }
    }
}

private fun upload(target: VoiceTakeTarget, take: CapturedTake) =
    RecorderTransition(RecorderState.Uploading(target, take), listOf(RecorderEffect.Upload(target, take)))

private fun permission(state: RecorderState.Permission, event: RecorderEvent): RecorderTransition = when (event) {
    RecorderEvent.PermissionGranted -> RecorderTransition(RecorderState.Starting(state.target), listOf(RecorderEffect.StartRecorder))
    RecorderEvent.PermissionRationaleNeeded ->
        if (state.status == PermissionStatus.REQUESTING) RecorderTransition(state.copy(status = PermissionStatus.RATIONALE)) else state.stay()
    is RecorderEvent.PermissionDenied ->
        RecorderTransition(state.copy(status = if (event.permanently) PermissionStatus.PERMANENTLY_DENIED else PermissionStatus.DENIED))
    RecorderEvent.RequestPermissionAgain ->
        if (state.status == PermissionStatus.REQUESTING) {
            state.stay()
        } else {
            RecorderTransition(state.copy(status = PermissionStatus.REQUESTING, requestId = state.requestId + 1))
        }
    else -> state.stay()
}

private fun recording(state: RecorderState.Recording, event: RecorderEvent, vad: VadConfig): RecorderTransition {
    fun finish(atMs: Long, breaks: (TapState) -> List<Long>, then: AfterFinish): RecorderTransition {
        val elapsed = (atMs - state.startedAtMs).coerceAtLeast(state.elapsedMs)
        val taps = state.taps.tick(elapsed)
        val take = CapturedTake(state.audio, elapsed, breaks(taps))
        return RecorderTransition(RecorderState.Finishing(state.target, take, then), listOf(RecorderEffect.FinishRecorder))
    }
    return when (event) {
        is RecorderEvent.Tap -> {
            val at = (event.atMs - state.startedAtMs).coerceAtLeast(0)
            val step = state.taps.down(at)
            if (step.stop) {
                val take = CapturedTake(state.audio, maxOf(at, state.elapsedMs), step.state.breaks)
                RecorderTransition(RecorderState.Finishing(state.target, take, AfterFinish.UPLOAD), listOf(RecorderEffect.FinishRecorder))
            } else {
                RecorderTransition(state.copy(taps = step.state))
            }
        }
        is RecorderEvent.Sample -> {
            val elapsed = (event.atMs - state.startedAtMs).coerceAtLeast(state.elapsedMs)
            val taps = state.taps.tick(elapsed)
            val step = state.vad.sample(elapsed, event.amplitude, state.silence, vad)
            if (step.stop) {
                finish(event.atMs, { it.finish() }, AfterFinish.UPLOAD)
            } else {
                RecorderTransition(state.copy(elapsedMs = elapsed, level = levelFraction(event.amplitude), taps = taps, vad = step.state))
            }
        }
        is RecorderEvent.SetSilence -> RecorderTransition(state.copy(silence = event.silence))
        is RecorderEvent.Stop -> finish(event.atMs, { it.finish() }, AfterFinish.UPLOAD)
        is RecorderEvent.Interrupt -> finish(event.atMs, { it.finish() }, AfterFinish.HOLD)
        else -> state.stay()
    }
}

private fun cancel(state: RecorderState): RecorderTransition = when (state) {
    RecorderState.Idle -> state.stay()
    is RecorderState.Permission, is RecorderState.Starting, is RecorderState.RecorderFailed, is RecorderState.Done ->
        RecorderTransition(RecorderState.Idle)
    is RecorderState.Recording -> RecorderTransition(RecorderState.Idle, listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(state.audio)))
    is RecorderState.Finishing -> RecorderTransition(RecorderState.Idle, listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(state.take.audio)))
    is RecorderState.Uploading -> RecorderTransition(RecorderState.Idle, listOf(RecorderEffect.CancelUpload, RecorderEffect.DeleteFile(state.take.audio)))
    is RecorderState.Held -> RecorderTransition(RecorderState.Idle, listOf(RecorderEffect.DeleteFile(state.take.audio)))
}
