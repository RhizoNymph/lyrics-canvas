package dev.nymph.lyrics.app.editor.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextRange
import dev.nymph.lyrics.app.editor.LINE_FIELD_SENTINEL

/**
 * "Add to word bank" for a text selection in the editor's line field, provided by the host
 * (the canvas entry's quick add); null hides the action.
 */
val LocalAddToWordBank = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The selected text of [raw] (the line field's value, sentinel removed), or null when nothing non-blank is selected. */
internal fun selectedText(raw: String, selection: TextRange): String? {
    if (selection.collapsed) return null
    val start = selection.min.coerceIn(0, raw.length)
    val end = selection.max.coerceIn(start, raw.length)
    return raw.substring(start, end).filterNot { it == LINE_FIELD_SENTINEL }.takeIf { it.isNotBlank() }
}
