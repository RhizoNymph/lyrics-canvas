package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.editor.LineFieldEdit
import dev.nymph.lyrics.app.editor.LineFieldState
import dev.nymph.lyrics.app.editor.enterSplit
import dev.nymph.lyrics.app.editor.interpretLineFieldChange
import dev.nymph.lyrics.logic.editor.CaretRequest

private fun LineFieldState.toValue(): TextFieldValue = TextFieldValue(raw, TextRange(selectionStart, selectionEnd))

private fun TextFieldValue.toField(): LineFieldState = LineFieldState(text, selection.min, selection.max)

/**
 * The active row's text input. It keeps its own [TextFieldValue] (cursor, IME composition) and
 * pushes text to the ViewModel. The value holds [dev.nymph.lyrics.app.editor.LINE_FIELD_SENTINEL]
 * at index 0 so Backspace at the row's start is visible as a value change on every keyboard (see
 * [interpretLineFieldChange]). It is rebuilt from the row's text when a structural edit issues a
 * new [caret] request (split, join, insert, delete), and takes the text back when the row became
 * arranged (a chunk edit rebuilt the text): typing always clears `arranged`, so a stale frame can
 * never overwrite what the user just typed.
 */
@Composable
internal fun ActiveLineField(
    text: String,
    arranged: Boolean,
    caret: CaretRequest?,
    onTextChange: (String) -> Unit,
    onSplit: (text: String, caret: Int) -> Unit,
    onBackspaceAtStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    var value by remember(caret) { mutableStateOf(LineFieldState.of(text, caret?.offset ?: text.length).toValue()) }
    LaunchedEffect(text, arranged) {
        val shown = value.toField().text
        if (arranged && shown != text) value = LineFieldState.of(text, text.length).toValue()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The field is never empty (the sentinel), so Material's placeholder would never show.
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                when (val edit = interpretLineFieldChange(value.toField(), next.toField())) {
                    // Not applied locally: the row changes (or, on a first non-empty row, nothing
                    // happens and the sentinel stays because the old value is kept).
                    LineFieldEdit.JoinPrevious -> onBackspaceAtStart()
                    is LineFieldEdit.Split -> onSplit(edit.text, edit.caret)
                    is LineFieldEdit.Edit -> {
                        // Keep the IME composition when the reported value needed no normalising.
                        value = if (edit.field == next.toField()) next else edit.field.toValue()
                        if (edit.textChanged) onTextChange(edit.field.text)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = {
                val split = enterSplit(value.toField())
                onSplit(split.text, split.caret)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        if (value.toField().text.isEmpty()) {
            Text(
                "Type lyrics…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            )
        }
    }
}
