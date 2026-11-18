package dev.nymph.lyrics.logic.score.play

import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PlaybackTest {
    private fun score(meter: ScoreMeter, bpm: Double, pickup: Int, vararg notes: ScoreNote) = Score(
        pickup = pickup,
        meters = listOf(meter),
        tempos = listOf(ScoreTempo(0, bpm)),
        snap = ScoreSnap.EIGHTH,
        lines = listOf(ScoreLine(notes.toList())),
    )

    @Test
    fun `4-4 at 120 clicks every half second with louder downbeats`() {
        val plan = playbackPlan(score(ScoreMeter(0, 4, 4), 120.0, 0, ScoreNote(0, 0, 24, 2), ScoreNote(1, 200, 24, 0)))
        assertEquals(8, plan.clicks.size, "two bars")
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0, 2000.0), plan.clicks.take(5).map { it.ms })
        assertEquals(listOf(true, false, false, false, true), plan.clicks.take(5).map { it.downbeat })
        assertEquals(4000.0, plan.durationMs)
        assertEquals(384, plan.endTick)
        assertEquals(PlayedNote(0.0, 250.0, 1.0f, 0, 0), plan.notes[0])
        assertEquals(0.5f, plan.notes[1].gain)
    }

    @Test
    fun `compound meters click on dotted beats`() {
        val plan = playbackPlan(score(ScoreMeter(0, 6, 8), 60.0, 0, ScoreNote(0, 0, 24, 0)))
        assertEquals(2, plan.clicks.size)
        assertEquals(1500.0, plan.clicks[1].ms, 1e-9)
    }

    @Test
    fun `a one-beat pickup clicks once before the first downbeat`() {
        val plan = playbackPlan(score(ScoreMeter(0, 4, 4), 120.0, 48, ScoreNote(0, 0, 24, 0)))
        assertEquals(Click(0.0, false), plan.clicks[0])
        assertEquals(Click(500.0, true), plan.clicks[1])
    }

    @Test
    fun `the synth renders the whole plan with sound where the notes are`() {
        val plan = playbackPlan(score(ScoreMeter(0, 4, 4), 120.0, 0, ScoreNote(0, 96, 48, 2)))
        val pcm = synthesize(plan, sampleRate = 8000, metronome = false)
        assertEquals(16000, pcm.size)
        val silent = (0 until 7900).maxOf { abs(pcm[it].toInt()) }
        val loud = (8100 until 8800).maxOf { abs(pcm[it].toInt()) }
        assertEquals(0, silent)
        assertTrue(loud > 2000, "note audible: $loud")
        val withClicks = synthesize(plan, sampleRate = 8000)
        assertTrue(abs(withClicks[30].toInt()) > 0, "the downbeat click at 0 ms")
    }
}
