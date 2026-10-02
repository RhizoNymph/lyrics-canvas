package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The active row's text input. It keeps its own [TextFieldValue] (cursor, IME composition) and
 * pushes text to the ViewModel. It takes the row's text back only when the row became arranged
 * (a chunk edit rebuilt the text): typing always clears `arranged`, so a stale frame can never
 * overwrite what the user just typed.
 */
@Composable
internal fun ActiveLineField(
    text: String,
    arranged: Boolean,
    onTextChange: (String) -> Unit,
    onNewLine: () -> Unit,
    onDeleteEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text, arranged) {
        if (arranged && value.text != text) value = TextFieldValue(text, TextRange(text.length))
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val changed = next.text != value.text
            value = next
            if (changed) onTextChange(next.text)
        },
        singleLine = true,
        placeholder = { Text("Type lyrics…") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onNewLine() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val emptyBackspace = event.type == KeyEventType.KeyDown && event.key == Key.Backspace && value.text.isEmpty()
                if (emptyBackspace) onDeleteEmpty()
                emptyBackspace
            },
    )
}
