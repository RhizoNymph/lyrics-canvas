package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.AudioMime
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.SplitMode
import dev.nymph.lyrics.model.voice.VoiceErrorCode
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val TARGET = VoiceTakeTarget.New(10.0, 20.0)
private val AUDIO = AudioFile("/cache/take.ogg", AudioMime.OGG)
private const val T0 = 100_000L

private val RESPONSE = VoiceTakeResponse(
    snippet = snippet("s9", rawText = "hello\nworld"),
    recording = Recording(RecordingId("r1"), SnippetId("s9"), "audio/ogg", 2_000, 900, "hello\nworld", SplitMode.TAPS, "2026-11-01T00:00:00Z"),
    lines = listOf("hello", "world"),
    splitMode = SplitMode.TAPS,
    created = true,
)

/** Feeds events in order, collecting every effect. */
private class Driver(var state: RecorderState = RecorderState.Idle) {
    val effects = mutableListOf<RecorderEffect>()

    fun on(vararg events: RecorderEvent): Driver {
        for (e in events) {
            val t = reduceRecorder(state, e)
            state = t.state
            effects += t.effects
        }
        return this
    }

    fun take(): List<RecorderEffect> = effects.toList().also { effects.clear() }
}

private fun recording(silence: SilenceTimeout = SilenceTimeout.OFF): Driver = Driver().on(
    RecorderEvent.Open(TARGET),
    RecorderEvent.PermissionGranted,
    RecorderEvent.RecorderStarted(AUDIO, T0, silence),
).also { it.take() }

class RecorderMachineTest {
    @Test
    fun `open asks for permission first`() {
        val d = Driver().on(RecorderEvent.Open(TARGET))
        assertEquals(RecorderState.Permission(TARGET, PermissionStatus.REQUESTING, 1), d.state)
        assertEquals(emptyList<RecorderEffect>(), d.take())
    }

    @Test
    fun `granted permission starts the recorder and its start begins the take`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.PermissionGranted)
        assertEquals(RecorderState.Starting(TARGET), d.state)
        assertEquals(listOf(RecorderEffect.StartRecorder), d.take())
        d.on(RecorderEvent.RecorderStarted(AUDIO, T0, SilenceTimeout.S3))
        val rec = d.state as RecorderState.Recording
        assertEquals(T0, rec.startedAtMs)
        assertEquals(0L, rec.elapsedMs)
        assertEquals(1, rec.lineNumber)
        assertEquals(SilenceTimeout.S3, rec.silence)
    }

    @Test
    fun `rationale, denial and asking again`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.PermissionRationaleNeeded)
        assertEquals(PermissionStatus.RATIONALE, (d.state as RecorderState.Permission).status)
        d.on(RecorderEvent.RequestPermissionAgain)
        assertEquals(RecorderState.Permission(TARGET, PermissionStatus.REQUESTING, 2), d.state)
        d.on(RecorderEvent.PermissionDenied(permanently = false))
        assertEquals(PermissionStatus.DENIED, (d.state as RecorderState.Permission).status)
        d.on(RecorderEvent.RequestPermissionAgain, RecorderEvent.PermissionDenied(permanently = true))
        assertEquals(RecorderState.Permission(TARGET, PermissionStatus.PERMANENTLY_DENIED, 3), d.state)
        // Granted from the app settings while permanently denied.
        d.on(RecorderEvent.PermissionGranted)
        assertEquals(RecorderState.Starting(TARGET), d.state)
    }

    @Test
    fun `asking again while already asking does not bump the request`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.RequestPermissionAgain)
        assertEquals(1, (d.state as RecorderState.Permission).requestId)
    }

    @Test
    fun `a failed start offers retry, which goes back through permission`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.PermissionGranted, RecorderEvent.RecorderStartFailed(RecorderFailure.START_FAILED))
        assertEquals(RecorderState.RecorderFailed(TARGET, RecorderFailure.START_FAILED), d.state)
        d.on(RecorderEvent.Retry)
        assertEquals(RecorderState.Permission(TARGET, PermissionStatus.REQUESTING, 1), d.state)
    }

    @Test
    fun `samples advance elapsed time and the level`() {
        val d = recording().on(RecorderEvent.Sample(T0 + 1_250, 32_767))
        val rec = d.state as RecorderState.Recording
        assertEquals(1_250L, rec.elapsedMs)
        assertEquals(1f, rec.level)
    }

    @Test
    fun `taps become breaks relative to the recorder start once the window passes`() {
        val d = recording().on(
            RecorderEvent.Tap(T0 + 2_000),
            RecorderEvent.Sample(T0 + 2_100, 100),
        )
        assertEquals(1, (d.state as RecorderState.Recording).lineNumber)
        d.on(RecorderEvent.Sample(T0 + 2_300, 100))
        assertEquals(2, (d.state as RecorderState.Recording).lineNumber)
        assertEquals(listOf(2_000L), (d.state as RecorderState.Recording).taps.breaks)
    }

    @Test
    fun `a double tap stops, drops its pending break, and uploads the take`() {
        val d = recording().on(
            RecorderEvent.Tap(T0 + 1_000),
            RecorderEvent.Sample(T0 + 1_400, 100),
            RecorderEvent.Tap(T0 + 5_000),
            RecorderEvent.Tap(T0 + 5_200),
        )
        val take = CapturedTake(AUDIO, 5_200, listOf(1_000))
        assertEquals(RecorderState.Finishing(TARGET, take, AfterFinish.UPLOAD), d.state)
        assertEquals(listOf(RecorderEffect.FinishRecorder), d.take())
        d.on(RecorderEvent.RecorderFinished)
        assertEquals(RecorderState.Uploading(TARGET, take), d.state)
        assertEquals(listOf(RecorderEffect.Upload(TARGET, take)), d.take())
    }

    @Test
    fun `the Stop button keeps a pending single tap and does not count as a tap`() {
        val d = recording().on(RecorderEvent.Tap(T0 + 1_000), RecorderEvent.Stop(T0 + 1_100))
        val finishing = d.state as RecorderState.Finishing
        assertEquals(CapturedTake(AUDIO, 1_100, listOf(1_000)), finishing.take)
    }

    @Test
    fun `silence auto-stop ends the take after speech`() {
        val d = recording(SilenceTimeout.S2)
        var t = T0
        repeat(10) { d.on(RecorderEvent.Sample(t, 100)); t += 50 }
        repeat(10) { d.on(RecorderEvent.Sample(t, 8_000)); t += 50 }
        while (d.state is RecorderState.Recording && t < T0 + 10_000) {
            d.on(RecorderEvent.Sample(t, 100))
            t += 50
        }
        val finishing = d.state as RecorderState.Finishing
        assertEquals(AfterFinish.UPLOAD, finishing.then)
        assertEquals(3_000L, finishing.take.durationMs) // 1 s in, plus 2 s of silence
    }

    @Test
    fun `changing the silence setting mid-take applies at once`() {
        val d = recording(SilenceTimeout.S2).on(RecorderEvent.SetSilence(SilenceTimeout.OFF))
        assertEquals(SilenceTimeout.OFF, (d.state as RecorderState.Recording).silence)
    }

    @Test
    fun `upload success is done and the local file goes, then acknowledge returns to idle`() {
        val d = recording().on(RecorderEvent.Stop(T0 + 3_000), RecorderEvent.RecorderFinished)
        d.take()
        d.on(RecorderEvent.UploadSucceeded(RESPONSE))
        assertEquals(RecorderState.Done(TARGET, RESPONSE), d.state)
        assertEquals(listOf(RecorderEffect.DeleteFile(AUDIO)), d.take())
        d.on(RecorderEvent.Acknowledge)
        assertEquals(RecorderState.Idle, d.state)
    }

    @Test
    fun `upload failure holds the take - retry uploads the same file, discard deletes it`() {
        val error = VoiceUploadError.Rejected(VoiceErrorCode.STT_UNAVAILABLE, 503, "down")
        val d = recording().on(RecorderEvent.Stop(T0 + 3_000), RecorderEvent.RecorderFinished, RecorderEvent.UploadFailed(error))
        val take = CapturedTake(AUDIO, 3_000, emptyList())
        assertEquals(RecorderState.Held(TARGET, take, HoldReason.UploadFailed(error)), d.state)
        d.take()
        d.on(RecorderEvent.Retry)
        assertEquals(RecorderState.Uploading(TARGET, take), d.state)
        assertEquals(listOf(RecorderEffect.Upload(TARGET, take)), d.take())
        d.on(RecorderEvent.UploadFailed(error), RecorderEvent.Discard)
        assertEquals(RecorderState.Idle, d.state)
        assertEquals(listOf(RecorderEffect.DeleteFile(AUDIO)), d.take())
    }

    @Test
    fun `an interruption finishes and holds the take instead of uploading`() {
        val d = recording().on(RecorderEvent.Tap(T0 + 500), RecorderEvent.Interrupt(T0 + 4_000))
        assertEquals(AfterFinish.HOLD, (d.state as RecorderState.Finishing).then)
        d.on(RecorderEvent.RecorderFinished)
        val held = d.state as RecorderState.Held
        assertEquals(HoldReason.Interrupted, held.reason)
        assertEquals(CapturedTake(AUDIO, 4_000, listOf(500)), held.take)
        d.take()
        d.on(RecorderEvent.Retry)
        assertTrue(d.state is RecorderState.Uploading)
    }

    @Test
    fun `interruption outside a take changes nothing`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.Interrupt(T0))
        assertEquals(RecorderState.Permission(TARGET, PermissionStatus.REQUESTING, 1), d.state)
    }

    @Test
    fun `a recorder that fails to finalise reports no audio and deletes the file`() {
        val d = recording().on(RecorderEvent.Stop(T0 + 50), RecorderEvent.RecorderFinishFailed)
        assertEquals(RecorderState.RecorderFailed(TARGET, RecorderFailure.NO_AUDIO), d.state)
        assertEquals(listOf(RecorderEffect.FinishRecorder, RecorderEffect.DeleteFile(AUDIO)), d.take())
    }

    @Test
    fun `cancel from every state returns to idle and cleans up`() {
        fun cancelFrom(d: Driver): List<RecorderEffect> {
            d.take()
            d.on(RecorderEvent.Cancel)
            assertEquals(RecorderState.Idle, d.state)
            return d.take()
        }
        assertEquals(emptyList<RecorderEffect>(), cancelFrom(Driver()))
        assertEquals(emptyList<RecorderEffect>(), cancelFrom(Driver().on(RecorderEvent.Open(TARGET))))
        assertEquals(emptyList<RecorderEffect>(), cancelFrom(Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.PermissionGranted)))
        assertEquals(listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(AUDIO)), cancelFrom(recording()))
        assertEquals(
            listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(AUDIO)),
            cancelFrom(recording().on(RecorderEvent.Stop(T0 + 1_000))),
        )
        assertEquals(
            listOf(RecorderEffect.CancelUpload, RecorderEffect.DeleteFile(AUDIO)),
            cancelFrom(recording().on(RecorderEvent.Stop(T0 + 1_000), RecorderEvent.RecorderFinished)),
        )
        assertEquals(
            listOf(RecorderEffect.DeleteFile(AUDIO)),
            cancelFrom(recording().on(RecorderEvent.Interrupt(T0 + 1_000), RecorderEvent.RecorderFinished)),
        )
    }

    @Test
    fun `a recorder that starts after cancel is aborted and its file deleted`() {
        val d = Driver().on(RecorderEvent.Open(TARGET), RecorderEvent.PermissionGranted, RecorderEvent.Cancel)
        d.take()
        d.on(RecorderEvent.RecorderStarted(AUDIO, T0, SilenceTimeout.OFF))
        assertEquals(RecorderState.Idle, d.state)
        assertEquals(listOf(RecorderEffect.AbortRecorder, RecorderEffect.DeleteFile(AUDIO)), d.take())
    }

    @Test
    fun `events that do not apply are ignored`() {
        val idle = Driver().on(RecorderEvent.Tap(T0), RecorderEvent.Stop(T0), RecorderEvent.Retry, RecorderEvent.UploadSucceeded(RESPONSE))
        assertEquals(RecorderState.Idle, idle.state)
        assertEquals(emptyList<RecorderEffect>(), idle.take())
        val rec = recording()
        val before = rec.state
        rec.on(RecorderEvent.Open(TARGET), RecorderEvent.Retry, RecorderEvent.PermissionGranted)
        assertEquals(before, rec.state)
    }

    @Test
    fun `the take's meta carries sorted breaks and the target`() {
        val meta = CapturedTake(AUDIO, 3_000, listOf(2_000, 1_000, 5_000)).meta(VoiceTakeTarget.Append(SnippetId("s1")))
        assertEquals(listOf(1_000L, 2_000L), meta.breaksMs)
        assertEquals(3_000L, meta.durationMs)
        assertEquals(VoiceTakeTarget.Append(SnippetId("s1")), meta.target)
    }

    @Test
    fun `upload errors map from api errors by code`() {
        val e = dev.nymph.lyrics.model.error.ApiError.fromHttp(503, """{"detail":{"error":"stt_unavailable","message":"STT down"}}""")
        assertEquals(VoiceUploadError.Rejected(VoiceErrorCode.STT_UNAVAILABLE, 503, "STT down"), toVoiceUploadError(e))
        val e413 = dev.nymph.lyrics.model.error.ApiError.fromHttp(413, """{"detail":{"error":"audio_too_large","message":"too big"}}""")
        assertEquals(VoiceUploadError.Rejected(VoiceErrorCode.AUDIO_TOO_LARGE, 413, "too big"), toVoiceUploadError(e413))
        val e422 = dev.nymph.lyrics.model.error.ApiError.fromHttp(422, """{"detail":{"error":"empty_transcript","message":"no words"}}""")
        assertEquals(VoiceUploadError.Rejected(VoiceErrorCode.EMPTY_TRANSCRIPT, 422, "no words"), toVoiceUploadError(e422))
        val e404 = dev.nymph.lyrics.model.error.ApiError.fromHttp(404, """{"detail":{"error":"snippet_not_found","message":"gone"}}""")
        assertEquals(VoiceUploadError.Rejected(VoiceErrorCode.SNIPPET_NOT_FOUND, 404, "gone"), toVoiceUploadError(e404))
        val unknown = dev.nymph.lyrics.model.error.ApiError.fromHttp(500, """{"detail":{"error":"new_thing","message":"hm"}}""")
        assertEquals(VoiceUploadError.Http(500, "hm"), toVoiceUploadError(unknown))
        assertEquals(VoiceUploadError.Http(502, "Server error (502)"), toVoiceUploadError(dev.nymph.lyrics.model.error.ApiError.fromHttp(502, "<html>")))
        assertEquals(VoiceUploadError.SessionExpired, toVoiceUploadError(dev.nymph.lyrics.model.error.ApiError.Unauthorized(401, null)))
        assertEquals(
            VoiceUploadError.Network("offline"),
            toVoiceUploadError(dev.nymph.lyrics.model.error.ApiError.Network(java.io.IOException("offline"))),
        )
    }
}
