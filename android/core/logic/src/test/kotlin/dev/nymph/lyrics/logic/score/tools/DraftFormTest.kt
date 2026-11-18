package dev.nymph.lyrics.logic.score.tools

import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.ScoreDraftRequest
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.SplitMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DraftFormTest {
    private fun rec(id: String, timings: Boolean, created: String = "2026-11-01T10:00:00Z") = Recording(
        id = RecordingId(id),
        snippetId = SnippetId("s1"),
        mimeType = "audio/ogg",
        durationMs = 4200,
        byteSize = 10,
        transcript = "x",
        splitMode = SplitMode.SINGLE,
        createdAt = created,
        hasTimings = timings,
    )

    private val takes = listOf(rec("new-no-timings", false), rec("mid", true), rec("old", true))

    private fun ready(form: DraftForm, recordings: List<Recording> = takes): ScoreDraftRequest =
        (form.check(recordings) as DraftFormCheck.Ready).request

    private fun problem(form: DraftForm, recordings: List<Recording> = takes): DraftFormProblem =
        (form.check(recordings) as DraftFormCheck.Invalid).problem

    @Test
    fun `the default grid form is 4-4, eighths, 90 bpm`() {
        assertEquals(ScoreDraftRequest.FromGrid(ScoreSnap.EIGHTH, DraftMeter(4, 4), 90.0), ready(DraftForm()))
    }

    @Test
    fun `grid form with every field set`() {
        val form = DraftForm(note = ScoreSnap.SIXTEENTH_TRIPLET, meterNum = " 7 ", meterDen = 8, bpm = "132.25")
        assertEquals(ScoreDraftRequest.FromGrid(ScoreSnap.SIXTEENTH_TRIPLET, DraftMeter(7, 8), 132.25), ready(form))
    }

    @Test
    fun `meter numerator must be 1 to 32`() {
        assertEquals(DraftFormProblem.METER_NUM, problem(DraftForm(meterNum = "0")))
        assertEquals(DraftFormProblem.METER_NUM, problem(DraftForm(meterNum = "33")))
        assertEquals(DraftFormProblem.METER_NUM, problem(DraftForm(meterNum = "")))
        assertEquals(DraftFormProblem.METER_NUM, problem(DraftForm(meterNum = "3.5")))
        assertEquals(DraftMeter(32, 4), ready(DraftForm(meterNum = "32")).meter)
        assertEquals(DraftMeter(1, 4), ready(DraftForm(meterNum = "1")).meter)
    }

    @Test
    fun `meter denominator must be a power of two up to 32`() {
        assertEquals(DraftFormProblem.METER_DEN, problem(DraftForm(meterDen = 3)))
        assertEquals(DraftFormProblem.METER_DEN, problem(DraftForm(meterDen = 64)))
        DRAFT_DENOMINATORS.forEach { assertEquals(it, ready(DraftForm(meterDen = it)).meter.den) }
        assertEquals(listOf(1, 2, 4, 8, 16, 32), DRAFT_DENOMINATORS)
    }

    @Test
    fun `grid bpm is required, a number, 20 to 400, at most two decimals`() {
        assertEquals(DraftFormProblem.BPM_REQUIRED, problem(DraftForm(bpm = " ")))
        assertEquals(DraftFormProblem.BPM_NOT_A_NUMBER, problem(DraftForm(bpm = "fast")))
        assertEquals(DraftFormProblem.BPM_NOT_A_NUMBER, problem(DraftForm(bpm = "NaN")))
        assertEquals(DraftFormProblem.BPM_NOT_A_NUMBER, problem(DraftForm(bpm = "Infinity")))
        assertEquals(DraftFormProblem.BPM_RANGE, problem(DraftForm(bpm = "19.99")))
        assertEquals(DraftFormProblem.BPM_RANGE, problem(DraftForm(bpm = "400.01")))
        assertEquals(DraftFormProblem.BPM_DECIMALS, problem(DraftForm(bpm = "90.125")))
        assertEquals(20.0, (ready(DraftForm(bpm = "20")) as ScoreDraftRequest.FromGrid).bpm)
        assertEquals(400.0, (ready(DraftForm(bpm = "400")) as ScoreDraftRequest.FromGrid).bpm)
        assertEquals(90.1, (ready(DraftForm(bpm = "90.1")) as ScoreDraftRequest.FromGrid).bpm)
        assertEquals(90.5, (ready(DraftForm(bpm = "90,5")) as ScoreDraftRequest.FromGrid).bpm)
    }

    @Test
    fun `recording form sends the picked take and a blank bpm as an estimate`() {
        val form = DraftForm(source = DraftSource.RECORDING, recordingId = RecordingId("old"), bpm = "")
        assertEquals(ScoreDraftRequest.FromRecording(RecordingId("old"), DraftMeter(4, 4), null), ready(form))
        assertEquals(ScoreDraftRequest.FromRecording(RecordingId("old"), DraftMeter(4, 4), 100.0), ready(form.copy(bpm = "100")))
        assertEquals(DraftFormProblem.BPM_RANGE, problem(form.copy(bpm = "500")))
    }

    @Test
    fun `recording form needs a take that has word timings`() {
        assertEquals(DraftFormProblem.NO_RECORDING, problem(DraftForm(source = DraftSource.RECORDING)))
        assertEquals(DraftFormProblem.NO_RECORDING, problem(DraftForm(source = DraftSource.RECORDING, recordingId = RecordingId("gone"))))
        assertEquals(
            DraftFormProblem.RECORDING_NO_TIMINGS,
            problem(DraftForm(source = DraftSource.RECORDING, recordingId = RecordingId("new-no-timings"))),
        )
    }

    @Test
    fun `the default take is the newest with timings`() {
        assertEquals(RecordingId("mid"), defaultDraftRecording(takes))
        assertNull(defaultDraftRecording(listOf(rec("a", false))))
        assertNull(defaultDraftRecording(emptyList()))
    }

    @Test
    fun `with recordings picks the default only when none is picked or the pick is gone`() {
        assertEquals(RecordingId("mid"), DraftForm().withRecordings(takes).recordingId)
        assertEquals(RecordingId("old"), DraftForm(recordingId = RecordingId("old")).withRecordings(takes).recordingId)
        assertEquals(RecordingId("mid"), DraftForm(recordingId = RecordingId("gone")).withRecordings(takes).recordingId)
    }

    @Test
    fun `switching to a recording clears the grid default tempo, back to grid restores it`() {
        val rec = DraftForm().switchSource(DraftSource.RECORDING)
        assertEquals("", rec.bpm)
        assertEquals(DraftSource.RECORDING, rec.source)
        assertEquals(DEFAULT_DRAFT_BPM, rec.switchSource(DraftSource.GRID).bpm)
        // A tempo the user typed survives either way.
        assertEquals("120", DraftForm(bpm = "120").switchSource(DraftSource.RECORDING).bpm)
        assertEquals("120", DraftForm(source = DraftSource.RECORDING, bpm = "120").switchSource(DraftSource.GRID).bpm)
    }

    @Test
    fun `problem messages`() {
        DraftFormProblem.entries.forEach { assert(it.message.isNotBlank()) }
        assertEquals("Tempo must be 20 to 400 bpm", DraftFormProblem.BPM_RANGE.message)
    }

    @Test
    fun `take labels show the time, length and whether it can be drafted`() {
        assertEquals("2026-11-01 10:00 · 0:04", takeLabel(rec("a", true)))
        assertEquals("2026-11-01 10:00 · 0:04 · no word timings", takeLabel(rec("a", false)))
        assertEquals("2026-11-01 10:00 · 1:05", takeLabel(rec("a", true).copy(durationMs = 65_400)))
    }
}
