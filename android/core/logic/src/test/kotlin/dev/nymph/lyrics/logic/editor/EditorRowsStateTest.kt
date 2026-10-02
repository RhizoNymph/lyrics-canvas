package dev.nymph.lyrics.logic.editor

import dev.nymph.lyrics.logic.syls
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorRowsStateTest {
    private fun c(syllable: String, wordIndex: Int, stress: Int = 0) = GridCell(syllable = syllable, wordIndex = wordIndex, stress = stress)
    private fun rest() = GridCell(rest = true)
    private fun apiLine(rawText: String, cells: List<GridCell>) = GridLine(cells, cells.count { !it.isRest }, rawText)
    private fun texts(s: EditorRowsState) = s.gridData?.lines?.map { it.rawText }

    @Test
    fun `starts an empty snippet with one blank active row and no grid`() {
        val s = initEditorState("", null)
        assertEquals(listOf(""), s.lines)
        assertNull(s.gridData)
        assertEquals(0, s.activeRow)
        assertEquals(0, s.arranged.size)
    }

    @Test
    fun `keeps saved grid lines whose text matches and pins them as arranged`() {
        val saved = GridData(listOf(apiLine("hello world", listOf(c("hel", 0, 1), c("lo", 0), rest(), c("world", 1, 1)))))
        val s = initEditorState("hello world", saved)
        assertEquals(-1, s.activeRow)
        assertEquals(listOf("hel", "lo", "·", "world"), syls(gridLineForRow(s, 0)))
        assertTrue(0 in s.arranged)
        assertEquals("hello", gridLineForRow(s, 0)?.cells?.get(0)?.word)
    }

    @Test
    fun `falls back to a local estimate when the saved grid does not match, and skips blank rows`() {
        val saved = GridData(listOf(apiLine("old text", listOf(c("old", 0), c("text", 1)))))
        val s = initEditorState("hello\n\nworld", saved)
        assertEquals(listOf("hello", "", "world"), s.lines)
        assertEquals(listOf("hello", "world"), texts(s))
        assertEquals(0, s.arranged.size)
        assertNull(gridLineForRow(s, 1))
        assertEquals("world", gridLineForRow(s, 2)?.rawText)
    }

    @Test
    fun `typeLine replaces the row grid line with a local estimate and un-arranges it`() {
        val saved = GridData(listOf(apiLine("hello", listOf(c("hel", 0), rest(), c("lo", 0)))))
        var s = initEditorState("hello", saved)
        s = typeLine(s, 0, "hello there")
        assertEquals("hello there", s.lines[0])
        assertFalse(0 in s.arranged)
        assertEquals(listOf("hel", "lo", "there"), syls(gridLineForRow(s, 0)))
    }

    @Test
    fun `typeLine adds and removes grid lines as rows become non-blank or blank`() {
        var s = initEditorState("a\nb", null)
        s = typeLine(s, 0, "")
        assertEquals(listOf("b"), texts(s))
        assertEquals("b", gridLineForRow(s, 1)?.rawText)
        s = typeLine(s, 0, "x")
        assertEquals(listOf("x", "b"), texts(s))
        s = typeLine(s, 0, "")
        s = typeLine(s, 1, "")
        assertNull(s.gridData)
    }

    @Test
    fun `keeps other arranged rows mapped when an earlier row goes blank`() {
        val saved = GridData(listOf(apiLine("a", listOf(c("a", 0))), apiLine("b", listOf(rest(), c("b", 0)))))
        var s = initEditorState("a\nb", saved)
        s = typeLine(s, 0, "")
        assertTrue(1 in s.arranged)
        assertEquals(listOf("·", "b"), syls(gridLineForRow(s, 1)))
    }

    @Test
    fun `insertRow adds a blank row below and shifts arranged indexes`() {
        val saved = GridData(listOf(apiLine("a", listOf(c("a", 0))), apiLine("b", listOf(c("b", 0)))))
        var s = initEditorState("a\nb", saved)
        s = insertRow(s, 0)
        assertEquals(listOf("a", "", "b"), s.lines)
        assertEquals(1, s.activeRow)
        assertEquals(listOf(0, 2), s.arranged.sorted())
        assertEquals(listOf("a", "b"), texts(s))
    }

    @Test
    fun `deleteRow removes the row and its grid line and shifts arranged indexes`() {
        val saved = GridData(listOf(apiLine("a", listOf(c("a", 0))), apiLine("b", listOf(c("b", 0)))))
        var s = initEditorState("a\nb", saved)
        s = deleteRow(s, 0)
        assertEquals(listOf("b"), s.lines)
        assertEquals(listOf("b"), texts(s))
        assertEquals(listOf(0), s.arranged.toList())
        assertEquals(0, s.activeRow)
    }

    @Test
    fun `deleteRow keeps at least one row`() {
        assertEquals(listOf(""), deleteRow(initEditorState("", null), 0).lines)
    }

    @Test
    fun `moveChunkInRow moves cells, rebuilds the text into lines and marks the row arranged`() {
        var s = initEditorState("hello world", null)
        s = moveChunkInRow(s, 0, 2, 3, 0)
        assertEquals(listOf("world", "hel", "lo"), syls(gridLineForRow(s, 0)))
        assertEquals("world hello", gridLineForRow(s, 0)?.rawText)
        assertEquals("world hello", s.lines[0])
        assertTrue(0 in s.arranged)
    }

    @Test
    fun `moveChunkInRow is a no-op on a blank row`() {
        val s = initEditorState("a\n\nb", null)
        assertSame(s, moveChunkInRow(s, 1, 0, 1, 0))
    }

    @Test
    fun `inserts a rest, keeps the text and marks the row arranged`() {
        var s = initEditorState("hello", null)
        s = insertRestInRow(s, 0, 1)
        assertEquals(listOf("hel", "·", "lo"), syls(gridLineForRow(s, 0)))
        assertEquals("hello", s.lines[0])
        assertTrue(0 in s.arranged)
    }

    @Test
    fun `removes a range of cells and rebuilds the text`() {
        var s = initEditorState("hello", null)
        s = insertRestInRow(s, 0, 1)
        s = insertRestInRow(s, 0, 1)
        s = removeCellsInRow(s, 0, 1, 3)
        assertEquals(listOf("hel", "lo"), syls(gridLineForRow(s, 0)))
        s = removeCellsInRow(s, 0, 0, 1)
        assertEquals("lo", s.lines[0])
    }

    private val reorderSaved = GridData(listOf(apiLine("a", listOf(c("a", 0))), apiLine("c", listOf(rest(), c("c", 0)))))

    @Test
    fun `reorderRows moves a row and carries its grid line, arranged flag and active state`() {
        var s = initEditorState("a\n\nc", reorderSaved)
        s = setActiveRow(s, 2)
        s = reorderRows(s, 2, 0)
        assertEquals(listOf("c", "a", ""), s.lines)
        assertEquals(listOf("c", "a"), texts(s))
        assertEquals(listOf("·", "c"), syls(gridLineForRow(s, 0)))
        assertEquals(listOf(0, 1), s.arranged.sorted())
        assertEquals(0, s.activeRow)
    }

    @Test
    fun `reorderRows shifts the active row when another row moves across it`() {
        var s = initEditorState("a\nb\nc", null)
        s = setActiveRow(s, 1)
        s = reorderRows(s, 2, 0)
        assertEquals(listOf("c", "a", "b"), s.lines)
        assertEquals(2, s.activeRow)
    }

    @Test
    fun `reorderRows is a no-op out of range`() {
        val s = initEditorState("a\nb", null)
        assertSame(s, reorderRows(s, 0, 2))
        assertSame(s, reorderRows(s, 1, 1))
    }

    @Test
    fun `mergeSyllabified replaces unarranged rows by matching text and attaches words`() {
        var s = initEditorState("hello world\nbye", null)
        val result = GridData(
            listOf(apiLine("hello world", listOf(c("hel", 0, 1), c("lo", 0), c("world", 1, 1))), apiLine("bye", listOf(c("bye", 0, 1)))),
        )
        s = mergeSyllabified(s, result)
        assertEquals(1, gridLineForRow(s, 0)?.cells?.get(0)?.stress)
        assertEquals("hello", gridLineForRow(s, 0)?.cells?.get(0)?.word)
        assertEquals(1, gridLineForRow(s, 1)?.cells?.get(0)?.stress)
    }

    @Test
    fun `mergeSyllabified leaves arranged rows untouched and keeps rows whose text no longer matches`() {
        var s = initEditorState("hello world\nbye", null)
        s = moveChunkInRow(s, 0, 2, 3, 0)
        s = typeLine(s, 1, "byebye")
        val result = GridData(
            listOf(apiLine("world hello", listOf(c("WORLD", 0, 1), c("hel", 1, 1), c("lo", 1))), apiLine("bye", listOf(c("bye", 0, 1)))),
        )
        s = mergeSyllabified(s, result)
        assertEquals(listOf("world", "hel", "lo"), syls(gridLineForRow(s, 0)))
        assertEquals("byebye", gridLineForRow(s, 1)?.rawText)
    }

    @Test
    fun `mergeSyllabified matches rows in order when two rows share the same text`() {
        var s = initEditorState("la\nla", null)
        s = mergeSyllabified(s, GridData(listOf(apiLine("la", listOf(c("la", 0, 1))), apiLine("la", listOf(c("la", 0, 2))))))
        assertEquals(1, gridLineForRow(s, 0)?.cells?.get(0)?.stress)
        assertEquals(2, gridLineForRow(s, 1)?.cells?.get(0)?.stress)
    }

    @Test
    fun `needsSyllabify is true only when some non-blank row is not arranged`() {
        val saved = GridData(listOf(apiLine("a", listOf(c("a", 0)))))
        assertFalse(needsSyllabify(initEditorState("a", saved)))
        assertTrue(needsSyllabify(initEditorState("a\nb", saved)))
        assertFalse(needsSyllabify(initEditorState("", null)))
    }

    @Test
    fun `rawTextForSave derives arranged rows from the grid and keeps typed text elsewhere`() {
        var s = initEditorState("hello world\n\nbye", null)
        s = moveChunkInRow(s, 0, 2, 3, 0)
        assertEquals("world hello\n\nbye", rawTextForSave(s))
    }
}
