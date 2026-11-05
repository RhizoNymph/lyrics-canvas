package dev.nymph.lyrics.app.editor.ui

import androidx.compose.ui.text.TextRange
import dev.nymph.lyrics.app.editor.LINE_FIELD_SENTINEL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SelectionActionsTest {
    private val raw = "${LINE_FIELD_SENTINEL}neon lights, rain"

    @Test
    fun `the selected text without the sentinel`() {
        assertEquals("neon", selectedText(raw, TextRange(1, 5)))
        assertEquals("neon", selectedText(raw, TextRange(0, 5)))
        assertEquals("neon lights, rain", selectedText(raw, TextRange(raw.length, 0)))
    }

    @Test
    fun `no selection or a blank one offers nothing`() {
        assertNull(selectedText(raw, TextRange(3)))
        assertNull(selectedText(raw, TextRange(5, 6)))
        assertNull(selectedText(raw, TextRange(0, 1)))
    }

    @Test
    fun `out of range selections are clamped`() {
        assertEquals("rain", selectedText(raw, TextRange(raw.length - 4, raw.length + 10)))
    }
}
