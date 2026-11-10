package dev.nymph.lyrics.logic.placements

import dev.nymph.lyrics.logic.tags.codePointLength

/** The longest canvas title the server accepts, in code points (after the strip). */
const val CANVAS_TITLE_MAX: Int = 255

enum class CanvasTitleProblem { EMPTY, TOO_LONG }

/** A local title check, mirroring the server's `invalid_canvas_title` rule. */
sealed interface CanvasTitleCheck {
    /** [title] is trimmed and 1..[CANVAS_TITLE_MAX] code points: what to send. */
    data class Valid(val title: String) : CanvasTitleCheck

    data class Invalid(val problem: CanvasTitleProblem) : CanvasTitleCheck
}

/** Python's `str.strip` whitespace: Unicode whitespace including no-break spaces and U+0085. */
private fun isPythonSpace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

/** Trim [raw] as the server strips it, then require 1..255 code points. */
fun checkCanvasTitle(raw: String): CanvasTitleCheck {
    val title = raw.trim(::isPythonSpace)
    return when {
        title.isEmpty() -> CanvasTitleCheck.Invalid(CanvasTitleProblem.EMPTY)
        codePointLength(title) > CANVAS_TITLE_MAX -> CanvasTitleCheck.Invalid(CanvasTitleProblem.TOO_LONG)
        else -> CanvasTitleCheck.Valid(title)
    }
}
