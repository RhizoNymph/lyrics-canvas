package dev.nymph.lyrics.logic.score.edit

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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StaffEditsTest {
    private fun line(vararg stress: Int) = GridLine(
        stress.mapIndexed { i, st -> GridCell(syllable = "s$i", stress = st, wordIndex = i, word = "s$i") },
        stress.size,
        stress.indices.joinToString(" ") { "s$it" },
    )

    /** Two lines: 3 syllables (stress 1 0 1) and 2 syllables. */
    private val grid = GridData(listOf(line(1, 0, 1), line(0, 1)))

    private fun score(vararg lines: List<ScoreNote>, pickup: Int = 0, meters: List<ScoreMeter> = listOf(ScoreMeter(0, 4, 4))) =
        Score(pickup = pickup, meters = meters, tempos = listOf(ScoreTempo(0, 90.0)), snap = ScoreSnap.EIGHTH, lines = lines.map { ScoreLine(it) })

    private fun n(s: Int, t: Int, d: Int = 24, a: Int = 0) = ScoreNote(s, t, d, a)

    private val empty = score(emptyList(), emptyList())

    private fun StaffEdit.score(): Score = (this as StaffEdit.Changed).score.also { assertNull(validateScore(it, grid), "edits keep the score valid") }

    private fun StaffEdit.refusal(): StaffRefusal = (this as StaffEdit.Refused).reason

    @Nested
    inner class Snapping {
        @Test
        fun `ticks snap to the nearest point of the bar's grid, ties to the earlier`() {
            assertEquals(24, snapTick(empty, 30, 24))
            assertEquals(48, snapTick(empty, 40, 24))
            assertEquals(0, snapTick(empty, 12, 24))
            assertEquals(192, snapTick(empty, 190, 24))
        }

        @Test
        fun `triplets restart at every bar line`() {
            val fiveEight = score(emptyList(), emptyList(), meters = listOf(ScoreMeter(0, 5, 8)))
            // 5/8 = 120 ticks; 1/8t = 16: 112 is the last point of bar 0, 120 the bar line.
            assertEquals(120, snapTick(fiveEight, 118, 16))
            assertEquals(136, snapTick(fiveEight, 135, 16))
        }

        @Test
        fun `the pickup's grid runs back from bar 0`() {
            val pick = score(emptyList(), emptyList(), pickup = 36)
            assertEquals(12, snapTick(pick, 10, 24))
            assertEquals(36, snapTick(pick, 30, 24))
            assertEquals(0, snapTick(pick, 2, 24))
        }

        @Test
        fun `lengths snap to whole units, at least one`() {
            assertEquals(24, snapLength(5, 24))
            assertEquals(48, snapLength(40, 24))
        }
    }

    @Nested
    inner class Placing {
        @Test
        fun `a tray syllable lands on the snapped tick with the snap length and its stress accent`() {
            val placed = placeSyllable(empty, grid, SyllableRef(0, 0), 30, 24).score()
            assertEquals(listOf(n(0, 24, a = 1)), placed.lines[0].notes)
            val second = placeSyllable(placed, grid, SyllableRef(0, 1), 100, 24).score()
            assertEquals(listOf(n(0, 24, a = 1), n(1, 96)), second.lines[0].notes)
        }

        @Test
        fun `a syllable cannot jump over its neighbours in score order`() {
            val s = score(listOf(n(0, 0), n(2, 96)), emptyList())
            // s1 must lie between s0 (ends 24) and s2 (starts 96).
            assertEquals(listOf(n(0, 0), n(1, 72), n(2, 96)), placeSyllable(s, grid, SyllableRef(0, 1), 300, 24).score().lines[0].notes)
            assertEquals(24, placeSyllable(s, grid, SyllableRef(0, 1), 0, 24).score().lines[0].notes[1].t)
            // Line 1 notes come after every note of line 0.
            assertEquals(120, placeSyllable(s, grid, SyllableRef(1, 0), 0, 24).score().lines[1].notes[0].t)
        }

        @Test
        fun `a squeeze shortens the note and no room refuses`() {
            val tight = score(listOf(n(0, 0), n(2, 36)), emptyList())
            assertEquals(n(1, 24, d = 12), placeSyllable(tight, grid, SyllableRef(0, 1), 24, 24).score().lines[0].notes[1])
            val full = score(listOf(n(0, 0), n(2, 24)), emptyList())
            assertEquals(StaffRefusal.NO_ROOM, placeSyllable(full, grid, SyllableRef(0, 1), 24, 24).refusal())
        }

        @Test
        fun `placing twice or out of range is refused`() {
            val s = score(listOf(n(0, 0)), emptyList())
            assertEquals(StaffRefusal.WRONG_PLACEMENT, placeSyllable(s, grid, SyllableRef(0, 0), 48, 24).refusal())
            assertEquals(StaffRefusal.NO_SUCH_SYLLABLE, placeSyllable(s, grid, SyllableRef(0, 3), 48, 24).refusal())
            assertEquals(StaffRefusal.NO_SUCH_SYLLABLE, placeSyllable(s, grid, SyllableRef(2, 0), 48, 24).refusal())
        }
    }

    @Nested
    inner class Editing {
        private val s = score(listOf(n(0, 0), n(1, 48), n(2, 96)), listOf(n(0, 192)))

        @Test
        fun `moving clamps between the neighbours and keeps the length and extra keys`() {
            val extra = score(listOf(n(0, 0), n(1, 48).copy(extra = mapOf("p" to JsonPrimitive(60))), n(2, 96)), listOf(n(0, 192)))
            val moved = moveNote(extra, SyllableRef(0, 1), 70, 24).score().lines[0].notes[1]
            assertEquals(72, moved.t)
            assertEquals(24, moved.d)
            assertEquals(JsonPrimitive(60), moved.extra["p"])
            assertEquals(24, moveNote(s, SyllableRef(0, 1), 0, 24).score().lines[0].notes[1].t)
            assertEquals(72, moveNote(s, SyllableRef(0, 1), 500, 24).score().lines[0].notes[1].t)
        }

        @Test
        fun `the right edge sets the length up to the next note`() {
            assertEquals(48, resizeNote(s, SyllableRef(0, 1), 100, 24).score().lines[0].notes[1].d)
            assertEquals(24, resizeNote(s, SyllableRef(0, 1), 40, 24).score().lines[0].notes[1].d, "never shorter than one snap from a too-early edge")
            assertEquals(96, resizeNote(s, SyllableRef(0, 2), 400, 24).score().lines[0].notes[2].d, "up to line 1's first note")
        }

        @Test
        fun `steps and nudges move by one snap within the window`() {
            assertEquals(48, stepLength(s, SyllableRef(0, 1), 1, 24).score().lines[0].notes[1].d)
            assertEquals(1, stepLength(s, SyllableRef(0, 0), -1, 24).score().lines[0].notes[0].d)
            assertEquals(72, nudgeNote(s, SyllableRef(0, 1), 1, 24).score().lines[0].notes[1].t)
            assertEquals(24, nudgeNote(s, SyllableRef(0, 1), -1, 24).score().lines[0].notes[1].t)
            assertEquals(0, nudgeNote(s, SyllableRef(0, 0), -1, 24).score().lines[0].notes[0].t)
        }

        @Test
        fun `accent cycles and unplace sends the syllable back to the tray`() {
            val a1 = cycleAccent(s, SyllableRef(0, 0)).score()
            assertEquals(1, a1.lines[0].notes[0].a)
            assertEquals(2, cycleAccent(a1, SyllableRef(0, 0)).score().lines[0].notes[0].a)
            assertEquals(0, cycleAccent(cycleAccent(a1, SyllableRef(0, 0)).score(), SyllableRef(0, 0)).score().lines[0].notes[0].a)
            val off = unplace(s, SyllableRef(0, 1)).score()
            assertEquals(listOf(0, 2), off.lines[0].notes.map { it.s })
            assertEquals(listOf(listOf(1), listOf(1)), unplacedSyllables(off, grid))
            assertEquals(StaffRefusal.WRONG_PLACEMENT, unplace(off, SyllableRef(0, 1)).refusal())
        }
    }
}
