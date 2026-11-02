package dev.nymph.lyrics.app.editor

/**
 * Soft keyboards do not reliably send a Backspace key event when the caret is at the start of a
 * non-empty field (Gboard and most IMEs call `deleteSurroundingText`, which is a silent no-op
 * there). So the active row's field always holds this zero-width space at index 0, with the caret
 * kept after it: Backspace at the row's logical start deletes the sentinel, which arrives as an
 * ordinary value change and is recognised by [interpretLineFieldChange]. Hardware keyboards take
 * the same path (Compose's Backspace deletes the character before the caret).
 */
const val LINE_FIELD_SENTINEL: Char = '​'

/**
 * The active row's field: [raw] is what the text field holds (sentinel included); the selection is
 * in raw offsets. [text] and [caret] are the row's logical text and caret.
 */
data class LineFieldState(val raw: String, val selectionStart: Int, val selectionEnd: Int) {
    val text: String get() = raw.filterNot { it == LINE_FIELD_SENTINEL }

    /** The logical selection start (the caret when collapsed). */
    val caret: Int get() = logicalOffset(raw, selectionStart)

    val caretEnd: Int get() = logicalOffset(raw, selectionEnd)

    companion object {
        /** The field for row text [text] with the caret at logical [caret] (clamped). */
        fun of(text: String, caret: Int): LineFieldState = of(text, caret, caret)

        fun of(text: String, start: Int, end: Int): LineFieldState {
            val s = start.coerceIn(0, text.length)
            val e = end.coerceIn(s, text.length)
            return LineFieldState("$LINE_FIELD_SENTINEL$text", s + 1, e + 1)
        }
    }
}

private fun logicalOffset(raw: String, rawOffset: Int): Int {
    val upTo = rawOffset.coerceIn(0, raw.length)
    return (0 until upTo).count { raw[it] != LINE_FIELD_SENTINEL }
}

/** What a change of the active row's field means. */
sealed interface LineFieldEdit {
    /** The row stays; the field shows [field] (normalised). [textChanged]: push the text to the ViewModel. */
    data class Edit(val field: LineFieldState, val textChanged: Boolean) : LineFieldEdit

    /** Backspace at the row's start: join onto the previous row (or delete an empty row). */
    data object JoinPrevious : LineFieldEdit

    /** Split the row, whose text becomes [text], at logical [caret]. */
    data class Split(val text: String, val caret: Int) : LineFieldEdit
}

/**
 * Interpret the field going from [previous] to [next] (raw states as the text field reports them).
 * Only the sentinel disappearing while the caret sat right after it (logical 0, collapsed) and the
 * rest of the text unchanged is a join; any other loss of the sentinel (select-all and type, an
 * IME replacing a range) just restores it. A newline (paste, IME Enter on a multi-line keyboard)
 * splits at the first one; further newlines become spaces.
 */
fun interpretLineFieldChange(previous: LineFieldState, next: LineFieldState): LineFieldEdit {
    val hadSentinel = previous.raw.firstOrNull() == LINE_FIELD_SENTINEL
    val caretAtStart = previous.selectionStart == 1 && previous.selectionEnd == 1
    if (hadSentinel && caretAtStart && next.raw == previous.raw.substring(1)) return LineFieldEdit.JoinPrevious

    val text = next.text
    val newline = text.indexOf('\n')
    if (newline >= 0) {
        val flattened = text.substring(0, newline) + text.substring(newline + 1).replace('\n', ' ')
        return LineFieldEdit.Split(flattened, newline)
    }
    val field = LineFieldState.of(text, next.caret, next.caretEnd)
    return LineFieldEdit.Edit(field, textChanged = text != previous.text)
}

/** The IME action (Enter / Next): split at the caret, replacing a selected range. */
fun enterSplit(field: LineFieldState): LineFieldEdit.Split {
    val text = field.text
    val start = minOf(field.caret, field.caretEnd)
    val end = maxOf(field.caret, field.caretEnd)
    return LineFieldEdit.Split(text.substring(0, start) + text.substring(end), start)
}
