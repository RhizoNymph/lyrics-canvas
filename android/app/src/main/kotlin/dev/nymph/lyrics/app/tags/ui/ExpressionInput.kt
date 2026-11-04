package dev.nymph.lyrics.app.tags.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.userMessage
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.tags.Completion
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.Suggestion
import dev.nymph.lyrics.logic.tags.codePointLength
import dev.nymph.lyrics.logic.tags.codePointOffsetOf
import dev.nymph.lyrics.logic.tags.utf16IndexOf
import dev.nymph.lyrics.model.UserTag

/** Underlines the code point at [position] (the error marker); nothing when it is past the end. */
private class ErrorMarker(private val position: Int) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (position >= codePointLength(text.text)) return TransformedText(text, OffsetMapping.Identity)
        val start = utf16IndexOf(text.text, position)
        val end = utf16IndexOf(text.text, position + 1)
        val marked = buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = Palette.Error, background = Palette.Error.copy(alpha = 0.2f), textDecoration = TextDecoration.Underline), start, end)
        }
        return TransformedText(marked, OffsetMapping.Identity)
    }
}

/**
 * The tag-expression field shared by the canvas filter bar and the rhyme sheet: live validation
 * (the error's character underlined, message below), and while focused, suggestion chips for the
 * word at the caret and tag pills that insert at the caret. Text and caret go to [onEdit] in code
 * points; the field follows [field] when it changes elsewhere (a preset, a pill, a completion).
 */
@Composable
fun ExpressionInput(
    field: ExpressionField,
    tags: List<UserTag>,
    label: String,
    onEdit: (text: String, caret: Int) -> Unit,
    onAccept: (Completion, Suggestion) -> Unit,
    onInsertTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown under a valid field, or after the error message (e.g. what still applies). */
    note: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(field.text, TextRange(utf16IndexOf(field.text, field.caret)))) }
    // Follow [field] only when it changes; while the state catches up with typing, keep the local value.
    var lastField by remember { mutableStateOf(field) }
    if (field != lastField) {
        lastField = field
        val caretIndex = utf16IndexOf(field.text, field.caret)
        if (value.text != field.text || value.selection.end != caretIndex) value = TextFieldValue(field.text, TextRange(caretIndex))
    }
    var focused by remember { mutableStateOf(false) }
    val error = field.error

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                value = next
                onEdit(next.text, codePointOffsetOf(next.text, next.selection.end))
            },
            label = { Text(label) },
            placeholder = { Text("e.g. love & !type:hook") },
            singleLine = true,
            isError = error != null,
            visualTransformation = if (error != null) ErrorMarker(error.position) else VisualTransformation.None,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            trailingIcon = trailingIcon,
            supportingText = when {
                error != null -> ({ Text(error.userMessage() + (note?.let { " · $it" } ?: ""), color = Palette.Error, fontSize = 11.sp) })
                note != null -> ({ Text(note, color = Palette.Muted, fontSize = 11.sp) })
                else -> null
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        if (focused) {
            val completion = field.completion(tags.map { it.name })
            if (completion.suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(completion.suggestions, key = { it.insertText }) { suggestion ->
                        AssistChip(
                            onClick = { onAccept(completion, suggestion) },
                            label = { Text(suggestion.insertText, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        )
                    }
                }
            }
            if (tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tags, key = { it.id.value }) { tag -> TagPill(tag.name, tag.color, onClick = { onInsertTag(tag.name) }) }
                }
            }
        }
    }
}

/** A muted helper line. */
@Composable
internal fun Hint(text: String, color: Color = Palette.Muted) = Text(text, color = color, fontSize = 11.sp)
