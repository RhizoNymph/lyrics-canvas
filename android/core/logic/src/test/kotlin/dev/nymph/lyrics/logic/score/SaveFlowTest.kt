package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.score.ScoreInvalidReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SaveFlowTest {
    private val old = grid(line("I", "go", "now"))
    private val stored = score(listOf(note(0, 0, 24), note(1, 24, 24), note(2, 48, 24)))

    @Test
    fun `no score stays no score`() {
        val out = applyScoreSave(null, old, old, "I go now", ScoreEdit.Keep).getOrNull()!!
        assertEquals(SavedScoreContent("I go now", old, null), out)
    }

    @Test
    fun `remove drops the score and keeps grid and text`() {
        assertEquals(SavedScoreContent("I go now", old, null), applyScoreSave(stored, old, old, "I go now", ScoreEdit.Remove).getOrNull())
    }

    @Test
    fun `a rest-only grid edit moves the notes`() {
        val new = grid(line("I", "go", "_", "_", "now"))
        val out = applyScoreSave(stored, old, new, "I go _ _ now", ScoreEdit.Keep).getOrNull()!!
        assertEquals(144, out.score!!.lines[0].notes[2].t)
        assertEquals(new.lines[0].cells, out.grid!!.lines[0].cells)
        assertEquals("I go _ _ now", out.rawText)
    }

    @Test
    fun `a text change reconciles, then the rest link writes the text`() {
        val spaced = score(listOf(note(0, 0, 24), note(1, 72, 24), note(2, 96, 24)))
        val linkedOld = applyRestLink(spaced, old).getOrNull()
        val new = grid(line("I", "go", "home"))
        val out = applyScoreSave(spaced, linkedOld, new, "\n  \nI go home", ScoreEdit.Keep).getOrNull()!!
        assertEquals(listOf(0, 1), out.score!!.lines[0].notes.map { it.s })
        // "go" kept its gap after "I": one rest written into the grid and the text line.
        assertEquals("I _ go home", out.grid!!.lines[0].rawText)
        assertEquals("\n  \nI _ go home", out.rawText)
    }

    @Test
    fun `a sent score is validated against the new grid and linked`() {
        val sent = score(listOf(note(0, 0, 24), note(1, 72, 24), note(2, 96, 24)))
        val out = applyScoreSave(null, old, old, "I go now", ScoreEdit.Set(sent)).getOrNull()!!
        assertEquals(sent, out.score)
        assertEquals("I _ go now", out.rawText)
        val bad = score(listOf(note(5, 0, 24)))
        assertEquals(ScoreInvalidReason.SYLLABLE_OUT_OF_RANGE, (applyScoreSave(null, old, old, "", ScoreEdit.Set(bad)).errorOrNull() as ScoreError.Invalid).reason)
    }

    @Test
    fun `a text that lacks the grid's lines is kept as given`() {
        val sent = score(listOf(note(0, 0, 24), note(1, 72, 24), note(2, 96, 24)))
        val out = applyScoreSave(null, old, old, "", ScoreEdit.Set(sent)).getOrNull()!!
        assertEquals("", out.rawText)
        assertEquals("I _ go now", out.grid!!.lines[0].rawText)
    }

    @Test
    fun `score view needs a score`() {
        assertTrue(scoreViewAfter(stored, true))
        assertFalse(scoreViewAfter(null, true))
        assertFalse(scoreViewAfter(stored, false))
        assertNull(applyScoreSave(stored, old, old, "x", ScoreEdit.Remove).getOrNull()!!.score)
    }
}
