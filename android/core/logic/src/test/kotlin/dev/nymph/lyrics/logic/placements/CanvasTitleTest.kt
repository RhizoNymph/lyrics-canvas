package dev.nymph.lyrics.logic.placements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasTitleTest {
    @Test
    fun `a title is trimmed`() {
        assertEquals(CanvasTitleCheck.Valid("Night drive"), checkCanvasTitle("  Night drive \n"))
        assertEquals(CanvasTitleCheck.Valid("a"), checkCanvasTitle("a"))
    }

    @Test
    fun `python whitespace counts as padding`() {
        assertEquals(CanvasTitleCheck.Valid("x"), checkCanvasTitle(" x\u0085　"))
    }

    @Test
    fun `blank is empty`() {
        assertEquals(CanvasTitleCheck.Invalid(CanvasTitleProblem.EMPTY), checkCanvasTitle(""))
        assertEquals(CanvasTitleCheck.Invalid(CanvasTitleProblem.EMPTY), checkCanvasTitle(" \t\n "))
    }

    @Test
    fun `at most 255 code points after the trim`() {
        assertEquals(CanvasTitleCheck.Valid("a".repeat(255)), checkCanvasTitle(" " + "a".repeat(255) + " "))
        assertEquals(CanvasTitleCheck.Invalid(CanvasTitleProblem.TOO_LONG), checkCanvasTitle("a".repeat(256)))
    }

    @Test
    fun `an emoji is one character`() {
        val emoji = "🎵" // one code point, two UTF-16 units
        assertEquals(CanvasTitleCheck.Valid(emoji.repeat(255)), checkCanvasTitle(emoji.repeat(255)))
        assertEquals(CanvasTitleCheck.Invalid(CanvasTitleProblem.TOO_LONG), checkCanvasTitle(emoji.repeat(256)))
    }
}
