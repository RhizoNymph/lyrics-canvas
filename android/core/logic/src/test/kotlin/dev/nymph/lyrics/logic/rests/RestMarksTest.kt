package dev.nymph.lyrics.logic.rests

import dev.nymph.lyrics.logic.editor.gridLineForRow
import dev.nymph.lyrics.logic.editor.initEditorState
import dev.nymph.lyrics.logic.editor.insertRestInRow
import dev.nymph.lyrics.logic.editor.mergeSyllabified
import dev.nymph.lyrics.logic.editor.moveChunkInRow
import dev.nymph.lyrics.logic.editor.rawTextForSave
import dev.nymph.lyrics.logic.editor.removeCellsInRow
import dev.nymph.lyrics.logic.editor.typeLine
import dev.nymph.lyrics.logic.grid.attachWords
import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.logic.rhyme.lineWordCount
import dev.nymph.lyrics.logic.rhyme.lineWords
import dev.nymph.lyrics.logic.rhyme.sliceGridLine
import dev.nymph.lyrics.logic.syls
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rest marks through the grid helpers and the editor reducers. */
class RestMarksTest {
    private fun c(syllable: String, wordIndex: Int, word: String? = null) = GridCell(syllable = syllable, wordIndex = wordIndex, word = word)
    private val rest = GridCell.REST

    @Test
    fun `lineWordTokens skips rests and splits on them`() {
        assertEquals(listOf("go", "now"), lineWordTokens("go_now"))
        assertEquals(emptyList<String>(), lineWordTokens(" _ _ "))
        assertEquals(emptyList<String>(), lineWordTokens(""))
    }

    @Test
    fun `displayLine is null only for a line of rests`() {
        assertNull(displayLine("_ __"))
        assertEquals("", displayLine("   "))
        assertEquals("a b", displayLine(" a_b "))
    }

    @Test
    fun `displayText keeps stanza breaks and text without marks`() {
        assertEquals("one\n\ntwo", displayText("one\n\ntwo"))
        assertEquals("", displayText(""))
        assertEquals("x", displayText("_\nx\n_"))
    }

    @Test
    fun `a line of rests syllabifies to rest cells only and counts no syllables`() {
        val line = localSyllabifyLine(" _ _ ")
        assertEquals(listOf(rest, rest), line.cells)
        assertEquals(0, line.syllableCount)
        assertEquals("_ _", line.rawText)
    }

    @Test
    fun `local syllabify numbers words around rests`() {
        val line = localSyllabifyLine("I_ hello")
        assertEquals(listOf("I", "·", "hel", "lo"), syls(line))
        assertEquals(listOf(0, null, 1, 1), line.cells.map { it.wordIndex })
        assertEquals(3, line.syllableCount)
    }

    @Test
    fun `attachWords reads word tokens, so rest marks do not shift words`() {
        val line = GridLine(listOf(c("go", 0), rest, c("now", 1)), 2, "go_now")
        assertEquals(listOf("go", null, "now"), attachWords(line).cells.map { it.word })
    }

    @Test
    fun `rhyme word ranges count word tokens only`() {
        val line = GridLine(listOf(c("I", 0), rest, c("go", 1), rest, c("home", 2)), 3, "I _ go _ home")
        assertEquals(listOf("I", "go", "home"), lineWords(line))
        assertEquals(3, lineWordCount(line))
        assertEquals("go home", sliceGridLine(line, AnchorRange(0, 1, 3)).rawText)
    }

    @Test
    fun `a typed rest survives as a rest cell and the text keeps its mark`() {
        var s = initEditorState("", null)
        s = typeLine(s, 0, "I _ go")
        assertEquals(listOf("I", "·", "go"), syls(gridLineForRow(s, 0)))
        // A syllabify result for the same text (rests included) is merged in.
        val api = GridData(listOf(GridLine(listOf(c("I", 0, "I"), rest, c("go", 1, "go")), 2, "I _ go")))
        s = mergeSyllabified(s, api)
        assertEquals(listOf("I", "·", "go"), syls(gridLineForRow(s, 0)))
        assertEquals("I _ go", rawTextForSave(s))
    }

    @Test
    fun `grid rests are written back into the text`() {
        var s = initEditorState("I go", null)
        s = insertRestInRow(s, 0, 1)
        assertEquals("I _ go", s.lines[0])
        assertEquals("I _ go", rawTextForSave(s))
        s = moveChunkInRow(s, 0, 1, 2, 0)
        assertEquals("_ I go", s.lines[0])
        s = removeCellsInRow(s, 0, 0, 1)
        assertEquals("I go", s.lines[0])
    }

    @Test
    fun `a legacy row with grid rests but no marks opens arranged and saves canonical text`() {
        val saved = GridData(listOf(GridLine(listOf(c("I", 0), rest, c("go", 1)), 2, "I go")))
        val s = initEditorState("I go", saved)
        assertTrue(0 in s.arranged)
        assertEquals(listOf("I", "·", "go"), syls(gridLineForRow(s, 0)))
        assertEquals("I _ go", rawTextForSave(s))
    }

    @Test
    fun `the arranged check compares display text and the row keeps its own spelling`() {
        val saved = GridData(listOf(GridLine(listOf(c("go", 0), rest, c("now", 1)), 2, "go _ now")))
        val s = initEditorState("go_now", saved)
        assertTrue(0 in s.arranged)
        assertEquals("go_now", gridLineForRow(s, 0)?.rawText)
        assertEquals("go _ now", rawTextForSave(s))
    }

    @Test
    fun `different words still do not match`() {
        val saved = GridData(listOf(GridLine(listOf(c("go", 0), rest), 1, "go _")))
        val s = initEditorState("stop _", saved)
        assertFalse(0 in s.arranged)
        assertEquals(listOf("stop", "·"), syls(gridLineForRow(s, 0)))
    }
}
