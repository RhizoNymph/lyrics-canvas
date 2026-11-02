package dev.nymph.lyrics.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LineFieldEditTest {
    private val s = LINE_FIELD_SENTINEL

    /** A raw field state (sentinel spelled out by the caller). */
    private fun raw(raw: String, start: Int, end: Int = start) = LineFieldState(raw, start, end)

    @Nested
    inner class Construction {
        @Test
        fun `of puts the sentinel first and offsets the caret by one`() {
            val f = LineFieldState.of("hello", 2)
            assertEquals("${s}hello", f.raw)
            assertEquals(3, f.selectionStart)
            assertEquals(3, f.selectionEnd)
            assertEquals("hello", f.text)
            assertEquals(2, f.caret)
        }

        @Test
        fun `of clamps the caret into the text`() {
            assertEquals(LineFieldState.of("hi", 2), LineFieldState.of("hi", 99))
            assertEquals(LineFieldState.of("hi", 0), LineFieldState.of("hi", -3))
        }
    }

    @Nested
    inner class Typing {
        @Test
        fun `a typed character is a plain edit that keeps the field as given`() {
            val prev = LineFieldState.of("helo", 3)
            val next = raw("${s}hello", 5)
            val edit = interpretLineFieldChange(prev, next)
            assertEquals(LineFieldEdit.Edit(next, textChanged = true), edit)
        }

        @Test
        fun `a selection-only change is an edit without a text change`() {
            val prev = LineFieldState.of("hello", 5)
            val next = raw("${s}hello", 1, 4)
            assertEquals(LineFieldEdit.Edit(next, textChanged = false), interpretLineFieldChange(prev, next))
        }

        @Test
        fun `a caret or selection before the sentinel is pushed after it`() {
            val prev = LineFieldState.of("hello", 5)
            assertEquals(LineFieldEdit.Edit(raw("${s}hello", 1), textChanged = false), interpretLineFieldChange(prev, raw("${s}hello", 0)))
            assertEquals(LineFieldEdit.Edit(raw("${s}hello", 1, 6), textChanged = false), interpretLineFieldChange(prev, raw("${s}hello", 0, 6)))
        }

        @Test
        fun `backspace inside the text is a plain edit`() {
            val prev = LineFieldState.of("hello", 1)
            val next = raw("${s}ello", 1)
            assertEquals(LineFieldEdit.Edit(next, textChanged = true), interpretLineFieldChange(prev, next))
        }
    }

    @Nested
    inner class BackspaceAtStart {
        @Test
        fun `deleting the sentinel with the caret at 0 joins with the previous row`() {
            val prev = LineFieldState.of("world", 0)
            assertEquals(LineFieldEdit.JoinPrevious, interpretLineFieldChange(prev, raw("world", 0)))
        }

        @Test
        fun `on an empty row too`() {
            val prev = LineFieldState.of("", 0)
            assertEquals(LineFieldEdit.JoinPrevious, interpretLineFieldChange(prev, raw("", 0)))
        }

        @Test
        fun `losing the sentinel in a wider edit restores it instead of joining`() {
            // Select-all including the sentinel, then type "x".
            val prev = raw("${s}hello", 0, 6)
            assertEquals(
                LineFieldEdit.Edit(raw("${s}x", 2), textChanged = true),
                interpretLineFieldChange(prev, raw("x", 1)),
            )
        }

        @Test
        fun `losing the sentinel when the caret was not at 0 restores it instead of joining`() {
            val prev = LineFieldState.of("hello", 2)
            assertEquals(
                LineFieldEdit.Edit(raw("${s}hello", 1), textChanged = false),
                interpretLineFieldChange(prev, raw("hello", 0)),
            )
        }

        @Test
        fun `stray sentinels inside the text are dropped`() {
            val prev = LineFieldState.of("ab", 1)
            assertEquals(
                LineFieldEdit.Edit(raw("${s}axb", 3), textChanged = true),
                interpretLineFieldChange(prev, raw("${s}a${s}xb", 4)),
            )
        }
    }

    @Nested
    inner class Newlines {
        @Test
        fun `a typed newline splits at its position`() {
            val prev = LineFieldState.of("helloworld", 5)
            assertEquals(LineFieldEdit.Split("helloworld", 5), interpretLineFieldChange(prev, raw("${s}hello\nworld", 7)))
        }

        @Test
        fun `a pasted multi-line text splits at the first newline and flattens the rest into the second row`() {
            val prev = LineFieldState.of("", 0)
            assertEquals(LineFieldEdit.Split("ab c", 1), interpretLineFieldChange(prev, raw("${s}a\nb\nc", 6)))
        }
    }

    @Nested
    inner class EnterAction {
        @Test
        fun `splits at the caret`() {
            assertEquals(LineFieldEdit.Split("hello world", 5), enterSplit(LineFieldState.of("hello world", 5)))
        }

        @Test
        fun `removes a selected range first`() {
            assertEquals(LineFieldEdit.Split("hellorld", 5), enterSplit(raw("${s}hello world", 6, 9)))
        }

        @Test
        fun `at the end yields a caret at the end (a blank row below)`() {
            val split = enterSplit(LineFieldState.of("hello", 5))
            assertEquals(5, split.caret)
            assertTrue(split.text == "hello")
            assertFalse(split.caret < split.text.length)
        }
    }
}
