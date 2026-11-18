package dev.nymph.lyrics.app.canvas.render

import dev.nymph.lyrics.app.fakes.line
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreLine
import dev.nymph.lyrics.model.score.ScoreMeter
import dev.nymph.lyrics.model.score.ScoreNote
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.score.ScoreTempo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cards and list rows of a snippet in the staff view draw a compact staff instead of the grid. */
class CompactStaffTest {
    private val score = Score(
        meters = listOf(ScoreMeter(0, 4, 4)),
        tempos = listOf(ScoreTempo(0, 90.0)),
        snap = ScoreSnap.EIGHTH,
        lines = listOf(
            ScoreLine(listOf(ScoreNote(0, 0, 24, 1), ScoreNote(1, 240, 24, 0))),
            ScoreLine(emptyList()),
        ),
    )

    private val base = snippet("s1", "one two\nthree", listOf(line("one:1:0", "two:0:1"), line("three:1:0")), width = 300.0)

    @Test
    fun `the staff view replaces grid, counts and fallback with the staff`() {
        val layout = cardLayout(base.copy(score = score, scoreView = true))
        assertNull(layout.grid)
        assertTrue(layout.cells.isEmpty())
        assertTrue(layout.counts.isEmpty())
        assertNull(layout.fallbackText)
        val staff = checkNotNull(layout.staff)
        assertEquals(2, staff.rows.size)
        val first = staff.rows[0]
        // Two bars (0..384) span the inner width 272.
        assertEquals(14.0, first.left)
        assertEquals(14.0 + 272.0, first.right, 1e-9)
        assertEquals(listOf(14.0 + 136.0), first.barXs)
        assertEquals(listOf("one", "two"), first.notes.map { it.label })
        assertEquals(1, first.notes[0].accent)
        assertTrue(staff.rows[1].notes.isEmpty())
    }

    @Test
    fun `the grid view, or no score, keeps the grid`() {
        assertNull(cardLayout(base.copy(score = score, scoreView = false)).staff)
        assertNull(cardLayout(base.copy(score = null, scoreView = true)).staff)
        assertNotNull(cardLayout(base.copy(score = score)).grid)
    }
}
