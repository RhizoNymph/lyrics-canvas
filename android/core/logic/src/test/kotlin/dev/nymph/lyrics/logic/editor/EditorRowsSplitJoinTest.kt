package dev.nymph.lyrics.logic.editor

import dev.nymph.lyrics.logic.grid.localSyllabifyLine
import dev.nymph.lyrics.logic.jsTrim
import dev.nymph.lyrics.logic.syls
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditorRowsSplitJoinTest {
    private fun c(syllable: String, wordIndex: Int, stress: Int = 0) = GridCell(syllable = syllable, wordIndex = wordIndex, stress = stress)
    private fun rest() = GridCell(rest = true)
    private fun apiLine(rawText: String, cells: List<GridCell>) = GridLine(cells, cells.count { !it.isRest }, rawText)
    private fun texts(s: EditorRowsState) = s.gridData?.lines?.map { it.rawText }
    private fun arranged(s: EditorRowsState) = s.arranged.sorted()
    private fun caret(s: EditorRowsState) = s.caret?.let { it.row to it.offset }

    private fun assertInvariant(s: EditorRowsState) {
        val nonBlank = s.lines.filter { jsTrim(it).isNotEmpty() }
        assertEquals(nonBlank.map { jsTrim(it) }, texts(s) ?: emptyList<String>())
        for (r in s.arranged) assertTrue(jsTrim(s.lines[r]).isNotEmpty())
    }

    private val helloArranged = GridData(listOf(apiLine("hello", listOf(c("hel", 0), rest(), c("lo", 0)))))

    @Nested
    inner class Split {
        @Test
        fun `splits mid-line - text before stays, text after becomes the next row, caret at its start`() {
            val s = splitRow(initEditorState("hello world\nbye", null), 0, 6)
            assertEquals(listOf("hello", "world", "bye"), s.lines)
            assertEquals(listOf("hello", "world", "bye"), texts(s))
            assertEquals(1, s.activeRow)
            assertEquals(1 to 0, caret(s))
            assertInvariant(s)
        }

        @Test
        fun `trims trailing whitespace of the kept part and leading whitespace of the moved part`() {
            val s = splitRow(initEditorState("one   two  three", null), 0, 5)
            assertEquals(listOf("one", "two  three"), s.lines)
            assertEquals(1 to 0, caret(s))
        }

        @Test
        fun `splits mid-word`() {
            val s = splitRow(initEditorState("yesterday", null), 0, 3)
            assertEquals(listOf("yes", "terday"), s.lines)
            assertEquals(localSyllabifyLine("yes"), gridLineForRow(s, 0))
            assertEquals(localSyllabifyLine("terday"), gridLineForRow(s, 1))
        }

        @Test
        fun `with the caret at the end inserts a blank row below and leaves the row untouched`() {
            val s0 = initEditorState("hello", helloArranged)
            val s = splitRow(s0, 0, 5)
            assertEquals(listOf("hello", ""), s.lines)
            assertEquals(1, s.activeRow)
            assertEquals(1 to 0, caret(s))
            assertEquals(listOf(0), arranged(s))
            assertEquals(listOf("hel", "·", "lo"), syls(gridLineForRow(s, 0)))
            assertEquals(insertRow(s0, 0).lines, s.lines)
        }

        @Test
        fun `treats a caret followed only by whitespace as the end`() {
            val s = splitRow(initEditorState("hello   ", null), 0, 5)
            assertEquals(listOf("hello   ", ""), s.lines)
            assertEquals(1, s.activeRow)
        }

        @Test
        fun `clamps an out-of-range caret to the end`() {
            assertEquals(listOf("hello", ""), splitRow(initEditorState("hello", null), 0, 99).lines)
        }

        @Test
        fun `with the caret at 0 opens a blank row above and carries the row down`() {
            val s = splitRow(initEditorState("hello", helloArranged), 0, 0)
            assertEquals(listOf("", "hello"), s.lines)
            assertEquals(listOf(1), arranged(s))
            assertEquals(listOf("hel", "·", "lo"), syls(gridLineForRow(s, 1)))
            assertEquals(1, s.activeRow)
            assertEquals(1 to 0, caret(s))
            assertInvariant(s)
        }

        @Test
        fun `with only whitespace before the caret drops that whitespace and carries the row down`() {
            val s = splitRow(initEditorState("  hello", null), 0, 2)
            assertEquals(listOf("", "hello"), s.lines)
            assertEquals(1 to 0, caret(s))
        }

        @Test
        fun `on an empty row inserts a blank row`() {
            val s = splitRow(initEditorState("a\n\nb", null), 1, 0)
            assertEquals(listOf("a", "", "", "b"), s.lines)
            assertEquals(2, s.activeRow)
            assertInvariant(s)
        }

        @Test
        fun `un-arranges both halves of a split arranged row and gives them local estimates`() {
            val saved = GridData(
                listOf(
                    apiLine("a", listOf(c("a", 0))),
                    apiLine("hello world", listOf(c("hel", 0), rest(), c("lo", 0), c("world", 1))),
                    apiLine("z", listOf(rest(), c("z", 0))),
                ),
            )
            val s = splitRow(initEditorState("a\nhello world\nz", saved), 1, 5)
            assertEquals(listOf("a", "hello", "world", "z"), s.lines)
            assertEquals(listOf(0, 3), arranged(s))
            assertEquals(localSyllabifyLine("hello"), gridLineForRow(s, 1))
            assertEquals(localSyllabifyLine("world"), gridLineForRow(s, 2))
            assertEquals(listOf("·", "z"), syls(gridLineForRow(s, 3)))
            assertTrue(needsSyllabify(s))
            assertInvariant(s)
        }

        @Test
        fun `splits the last row`() {
            val s = splitRow(initEditorState("a\nb c", null), 1, 1)
            assertEquals(listOf("a", "b", "c"), s.lines)
            assertEquals(2, s.activeRow)
        }

        @Test
        fun `is a no-op for an out-of-range row`() {
            val s = initEditorState("a", null)
            assertSame(s, splitRow(s, 3, 0))
            assertSame(s, splitRow(s, -1, 0))
        }

        @Test
        fun `lets a syllabify result refine the new rows`() {
            var s = splitRow(initEditorState("hello world", null), 0, 5)
            s = mergeSyllabified(
                s,
                GridData(listOf(apiLine("hello", listOf(c("HEL", 0, 1), c("lo", 0))), apiLine("world", listOf(c("WORLD", 0, 1))))),
            )
            assertEquals(listOf("HEL", "lo"), syls(gridLineForRow(s, 0)))
            assertEquals(listOf("WORLD"), syls(gridLineForRow(s, 1)))
        }
    }

    @Nested
    inner class JoinPrevious {
        @Test
        fun `joins a row onto the previous one with a single space, caret at the join point`() {
            var s = initEditorState("hello\nworld\nbye", null)
            s = setActiveRow(s, 1)
            s = joinWithPrevious(s, 1)
            assertEquals(listOf("hello world", "bye"), s.lines)
            assertEquals(listOf("hello world", "bye"), texts(s))
            assertEquals(0, s.activeRow)
            assertEquals(0 to 6, caret(s))
            assertInvariant(s)
        }

        @Test
        fun `adds no space when the previous row already ends with one`() {
            val s = joinWithPrevious(initEditorState("hello \nworld", null), 1)
            assertEquals(listOf("hello world"), s.lines)
            assertEquals(0 to 6, caret(s))
        }

        @Test
        fun `adds no space when the joined row already starts with one`() {
            val s = joinWithPrevious(initEditorState("hello\n  world", null), 1)
            assertEquals(listOf("hello  world"), s.lines)
            assertEquals(0 to 5, caret(s))
        }

        @Test
        fun `keeps multiple spaces as they are`() {
            assertEquals(listOf("a    b"), joinWithPrevious(initEditorState("a  \n  b", null), 1).lines)
        }

        @Test
        fun `on an empty row deletes it and puts the caret at the end of the previous row`() {
            val s = joinWithPrevious(initEditorState("hello\n", helloArranged), 1)
            assertEquals(listOf("hello"), s.lines)
            assertEquals(listOf(0), arranged(s))
            assertEquals(0 to 5, caret(s))
            assertEquals(0, s.activeRow)
        }

        @Test
        fun `onto an empty previous row moves the row up keeping its grid and arranged flag, caret at 0`() {
            val s = joinWithPrevious(initEditorState("\nhello", helloArranged), 1)
            assertEquals(listOf("hello"), s.lines)
            assertEquals(listOf(0), arranged(s))
            assertEquals(listOf("hel", "·", "lo"), syls(gridLineForRow(s, 0)))
            assertEquals(0 to 0, caret(s))
        }

        @Test
        fun `un-arranges the joined row and re-estimates its grid`() {
            val saved = GridData(
                listOf(
                    apiLine("hello", listOf(c("hel", 0), rest(), c("lo", 0))),
                    apiLine("world", listOf(rest(), c("world", 0))),
                    apiLine("z", listOf(c("z", 0))),
                ),
            )
            val s = joinWithPrevious(initEditorState("hello\nworld\nz", saved), 1)
            assertEquals(listOf("hello world", "z"), s.lines)
            assertEquals(listOf(1), arranged(s))
            assertEquals(localSyllabifyLine("hello world"), gridLineForRow(s, 0))
            assertEquals("hello world\nz", rawTextForSave(s))
            assertInvariant(s)
        }

        @Test
        fun `is a no-op on the first row or out of range`() {
            val s = initEditorState("a\nb", null)
            assertSame(s, joinWithPrevious(s, 0))
            assertSame(s, joinWithPrevious(s, 2))
        }

        @Test
        fun `round-trips with splitRow at the join point`() {
            val joined = joinWithPrevious(initEditorState("hello\nworld", null), 1)
            assertEquals(listOf("hello", "world"), splitRow(joined, 0, joined.caret?.offset ?: 0).lines)
        }
    }

    @Nested
    inner class JoinNext {
        @Test
        fun `joins the next row onto this one, caret stays at the old end`() {
            val s = joinWithNext(initEditorState("hello\nworld\nbye", null), 0)
            assertEquals(listOf("hello world", "bye"), s.lines)
            assertEquals(0, s.activeRow)
            assertEquals(0 to 5, caret(s))
            assertInvariant(s)
        }

        @Test
        fun `removes an empty next row without touching this row`() {
            val s = joinWithNext(initEditorState("hello\n", helloArranged), 0)
            assertEquals(listOf("hello"), s.lines)
            assertEquals(listOf(0), arranged(s))
            assertEquals(0 to 5, caret(s))
        }

        @Test
        fun `pulls a row up into an empty row`() {
            val s = joinWithNext(initEditorState("\nworld", null), 0)
            assertEquals(listOf("world"), s.lines)
            assertEquals(0 to 0, caret(s))
        }

        @Test
        fun `is a no-op on the last row`() {
            val s = initEditorState("a\nb", null)
            assertSame(s, joinWithNext(s, 1))
        }
    }

    @Nested
    inner class CaretRequests {
        @Test
        fun `survive typing, grid edits and syllabify merges but are cleared by focusing another row or reordering`() {
            var s = splitRow(initEditorState("hello world", null), 0, 5)
            val req = s.caret
            s = typeLine(s, 1, "worlds")
            assertSame(req, s.caret)
            s = mergeSyllabified(s, GridData(emptyList()))
            assertSame(req, s.caret)
            s = moveChunkInRow(s, 0, 0, 1, 1)
            assertSame(req, s.caret)
            assertNull(setActiveRow(s, 0).caret)
            assertNull(reorderRows(s, 0, 1).caret)
        }

        @Test
        fun `are new objects on each structural edit so repeated identical requests are distinguishable`() {
            val a = joinWithNext(initEditorState("hello\n\n", null), 0)
            val b = joinWithNext(a, 0)
            assertEquals(caret(a), caret(b))
            assertNotSame(a.caret, b.caret)
        }

        @Test
        fun `insertRow and deleteRow request the caret at the start of the new row or the end of the row above`() {
            assertEquals(1 to 0, caret(insertRow(initEditorState("a", null), 0)))
            assertEquals(0 to 3, caret(deleteRow(initEditorState("abc\nx", null), 1)))
        }

        @Test
        fun `start out empty`() {
            assertNull(initEditorState("a", null).caret)
        }
    }
}
