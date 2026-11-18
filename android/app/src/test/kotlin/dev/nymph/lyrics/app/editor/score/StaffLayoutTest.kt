package dev.nymph.lyrics.app.editor.score

import dev.nymph.lyrics.app.editor.score.ui.StaffGeometry
import dev.nymph.lyrics.app.editor.score.ui.StaffHit
import dev.nymph.lyrics.app.editor.score.ui.StaffMetrics
import dev.nymph.lyrics.logic.score.edit.SyllableRef
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaffLayoutTest {
    private val grid = GridData(listOf(GridLine(listOf(GridCell(syllable = "hel"), GridCell(syllable = "lo")), 2, "hello")))

    private fun score(pickup: Int = 0, vararg notes: ScoreNote) = Score(
        pickup = pickup,
        meters = listOf(ScoreMeter(0, 4, 4), ScoreMeter(2, 3, 4)),
        tempos = listOf(ScoreTempo(0, 90.0), ScoreTempo(2, 120.5)),
        snap = ScoreSnap.EIGHTH,
        lines = listOf(ScoreLine(notes.toList())),
    )

    /** 1 px per tick, 400 px usable: two 4/4 bars fit a row. */
    private val metrics = StaffMetrics(width = 420f, pxPerTick = 1f, rowHeight = 80f, leftPad = 10f, rightPad = 10f)

    @Test
    fun `bars wrap into rows with meter and tempo labels where they change`() {
        val g = StaffGeometry(score(0, ScoreNote(0, 0, 24, 1), ScoreNote(1, 400, 24, 0)), grid, metrics)
        // Notes end at 424 -> bar 2 (starts 384, 3/4) -> 3 bars + 1 spare.
        assertEquals(listOf(0, 384), g.rows.map { it.startTick })
        assertEquals(listOf(0, 1), g.rows[0].bars.map { it.bar })
        assertEquals("4/4", g.rows[0].bars[0].meter)
        assertEquals("♩=90", g.rows[0].bars[0].tempo)
        assertEquals("3/4", g.rows[1].bars[0].meter)
        assertEquals("♩=120.5", g.rows[1].bars[0].tempo)
        assertEquals(null, g.rows[1].bars[1].meter)
        assertEquals(3, g.rows[0].beats.size / 2, "three inner beats per 4/4 bar")
    }

    @Test
    fun `the pickup is its own segment before bar 0`() {
        val g = StaffGeometry(score(48, ScoreNote(0, 0, 24, 0)), grid, metrics)
        assertEquals(listOf(-1, 0), g.rows[0].bars.map { it.bar }.take(2))
        assertEquals(10f + 48f, g.rows[0].bars[1].x)
    }

    @Test
    fun `notes have boxes with their syllables and a note across a row break is split`() {
        val g = StaffGeometry(score(0, ScoreNote(0, 0, 24, 2), ScoreNote(1, 360, 48, 0)), grid, metrics)
        val first = g.notes.first { it.ref == SyllableRef(0, 0) }
        assertEquals("hel", first.label)
        assertEquals(10f, first.left)
        assertEquals(34f, first.right)
        assertEquals(2, first.accent)
        val split = g.notes.filter { it.ref == SyllableRef(0, 1) }
        assertEquals(2, split.size)
        assertTrue(split[0].head && !split[0].tail)
        assertTrue(!split[1].head && split[1].tail)
        assertEquals(listOf(0 to 10f), g.lines.map { it.line to it.x })
    }

    @Test
    fun `touches hit the length handle, the body or empty staff`() {
        val g = StaffGeometry(score(0, ScoreNote(0, 48, 48, 0)), grid, metrics)
        assertEquals(StaffHit.Edge(SyllableRef(0, 0)), g.hit(10f + 94f, 30f, 12f))
        assertEquals(StaffHit.Body(SyllableRef(0, 0), 12), g.hit(10f + 60f, 30f, 12f))
        assertEquals(StaffHit.Empty(150), g.hit(10f + 150f, 30f, 12f))
        assertEquals(192, g.tickAt(10f + 192f, 10f))
        assertFalse(g.rows.isEmpty())
    }

    @Test
    fun `px per tick shrinks so the longest bar fits a row`() {
        val narrow = StaffGeometry(score(0, ScoreNote(0, 0, 24, 0)), grid, metrics.copy(width = 116f))
        assertEquals(0.5f, narrow.pxPerTick)
        assertEquals(narrow.rows.size * 80f, narrow.height)
    }
}
