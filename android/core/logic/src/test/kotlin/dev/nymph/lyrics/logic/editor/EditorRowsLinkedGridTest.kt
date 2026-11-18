package dev.nymph.lyrics.logic.editor

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [withLinkedGrid]: the score layer's rest-linked grid written back into the rows. */
class EditorRowsLinkedGridTest {
    private fun w(text: String, index: Int) = GridCell(syllable = text, stress = 1, wordIndex = index, word = text)
    private val rest = GridCell(rest = true)
    private fun line(vararg cells: GridCell) = GridLine(cells.toList(), cells.count { !it.isRest }, "")

    private val saved = GridData(
        listOf(
            line(w("I", 0), w("go", 1), w("now", 2)).copy(rawText = "I go now"),
            line(w("then", 0)).copy(rawText = "then"),
        ),
    )

    private val state = initEditorState("I go now\n\nthen", saved)

    @Test
    fun `a changed line takes the linked cells, a rebuilt text and becomes arranged`() {
        val linked = GridData(listOf(line(w("I", 0), rest, w("go", 1), w("now", 2)).copy(rawText = "I _ go now"), saved.lines[1]))
        val next = withLinkedGrid(state, linked)
        assertEquals(listOf("I _ go now", "", "then"), next.lines)
        assertEquals(linked.lines[0].cells, next.gridData!!.lines[0].cells)
        assertTrue(0 in next.arranged)
        assertEquals("I _ go now", rawTextForSave(next).lines().first())
        assertEquals(saved.lines[1], next.gridData!!.lines[1])
    }

    @Test
    fun `an equal grid or another line count leaves the state as it is`() {
        assertSame(state, withLinkedGrid(state, state.gridData))
        assertSame(state, withLinkedGrid(state, GridData(listOf(saved.lines[0]))))
        assertSame(state, withLinkedGrid(state, null))
        assertFalse(withLinkedGrid(state, null).lines.isEmpty())
    }
}
