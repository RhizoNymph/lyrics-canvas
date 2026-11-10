package dev.nymph.lyrics.app.canvas.rename

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import dev.nymph.lyrics.app.ui.theme.Palette

/** The rename dialog of [state] (nothing when closed); the field starts at the current title. */
@Composable
fun RenameCanvasDialog(state: RenameDialog, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val open = state as? RenameDialog.Open ?: return
    var text by rememberSaveable(open.canvasId.value) { mutableStateOf(open.initial) }
    AlertDialog(
        onDismissRequest = { if (!open.saving) onDismiss() },
        title = { Text("Rename canvas") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Title") },
                singleLine = true,
                enabled = !open.saving,
                isError = open.error != null,
                supportingText = open.error?.let { message -> { Text(message, color = Palette.Error) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = !open.saving) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !open.saving) { Text("Cancel") } },
    )
}
