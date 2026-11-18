package dev.nymph.lyrics.logic.score.edit

import dev.nymph.lyrics.logic.score.barStartTick
import dev.nymph.lyrics.logic.score.metricWeight
import dev.nymph.lyrics.logic.score.validateScore
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BarEditsTest {
    private val grid = GridData(listOf(GridLine(listOf(GridCell(syllable = "a"), GridCell(syllable = "b")), 2, "a b")))
    private val s = Score(
        meters = listOf(ScoreMeter(0, 4, 4)),
        tempos = listOf(ScoreTempo(0, 90.0)),
        snap = ScoreSnap.EIGHTH,
        lines = listOf(ScoreLine(listOf(ScoreNote(0, 0, 24, 1), ScoreNote(1, 48, 24, 0)))),
    )

    private fun StaffEdit.score(): Score = (this as StaffEdit.Changed).score.also { assertNull(validateScore(it, grid)) }

    private fun StaffEdit.refusal() = (this as StaffEdit.Refused).reason

    @Test
    fun `a meter change at a bar is inserted in order and replaces one at the same bar`() {
        val a = setMeter(s, 2, 3, 4).score()
        assertEquals(listOf(ScoreMeter(0, 4, 4), ScoreMeter(2, 3, 4)), a.meters)
        val b = setMeter(a, 2, 6, 8).score()
        assertEquals(listOf(ScoreMeter(0, 4, 4), ScoreMeter(2, 6, 8)), b.meters)
        assertEquals(listOf(ScoreMeter(0, 7, 8), ScoreMeter(2, 6, 8)), setMeter(b, 0, 7, 8).score().meters)
        assertEquals(s.lines, b.lines, "notes keep their ticks")
    }

    @Test
    fun `invalid meters and bars are refused, and bar 0 cannot be removed`() {
        assertEquals(StaffRefusal.OUT_OF_RANGE, setMeter(s, 1, 0, 4).refusal())
        assertEquals(StaffRefusal.OUT_OF_RANGE, setMeter(s, 1, 4, 3).refusal())
        assertEquals(StaffRefusal.OUT_OF_RANGE, setMeter(s, Score.MAX_BAR + 1, 4, 4).refusal())
        assertEquals(StaffRefusal.FIRST_ENTRY, removeMeter(s, 0).refusal())
        assertEquals(StaffRefusal.OUT_OF_RANGE, removeMeter(s, 3).refusal())
        assertEquals(s.meters, removeMeter(setMeter(s, 3, 2, 4).score(), 3).score().meters)
    }

    @Test
    fun `a shorter first bar cuts the pickup`() {
        val picked = setPickup(s, 144).score()
        assertEquals(47, setMeter(picked, 0, 1, 4).score().pickup)
    }

    @Test
    fun `tempo changes round to two decimals and stay in range`() {
        val t = setTempo(s, 4, 72.456).score()
        assertEquals(listOf(ScoreTempo(0, 90.0), ScoreTempo(4, 72.46)), t.tempos)
        assertEquals(StaffRefusal.OUT_OF_RANGE, setTempo(s, 1, 19.99).refusal())
        assertEquals(StaffRefusal.OUT_OF_RANGE, setTempo(s, 1, Double.NaN).refusal())
        assertEquals(listOf(ScoreTempo(0, 120.0)), setTempo(s, 0, 120.0).score().tempos)
        assertEquals(StaffRefusal.FIRST_ENTRY, removeTempo(s, 0).refusal())
        assertEquals(s.tempos, removeTempo(t, 4).score().tempos)
    }

    @Test
    fun `the pickup moves the notes with bar 0 so their beats stay`() {
        val p = setPickup(s, 48).score()
        assertEquals(48, p.pickup)
        assertEquals(listOf(48, 96), p.lines[0].notes.map { it.t })
        assertEquals(48L, barStartTick(p, 0))
        assertEquals(3, metricWeight(p, p.lines[0].notes[0].t))
        val back = setPickup(p, 0).score()
        assertEquals(s, back)
    }

    @Test
    fun `shrinking the pickup stops at tick 0 and out-of-range pickups are refused`() {
        val moved = setPickup(s, 48).score().let { sc -> sc.copy(lines = listOf(ScoreLine(listOf(ScoreNote(0, 24, 24, 1), ScoreNote(1, 96, 24, 0))))) }
        val shrunk = setPickup(moved, 0).score()
        assertEquals(24, shrunk.pickup)
        assertEquals(0, shrunk.lines[0].notes[0].t)
        assertEquals(StaffRefusal.OUT_OF_RANGE, setPickup(s, 192).refusal())
        assertEquals(StaffRefusal.OUT_OF_RANGE, setPickup(s, -1).refusal())
    }

    @Test
    fun `snap is just the preference`() {
        assertEquals(ScoreSnap.SIXTEENTH_TRIPLET, setSnap(s, ScoreSnap.SIXTEENTH_TRIPLET).snap)
    }
}
