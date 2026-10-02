package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.model.SnippetType

internal val SnippetType.label: String get() = name

/** Snippet type selector, Discard (no undo in v1) and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTopBar(
    snippetType: SnippetType,
    saving: Boolean,
    onSnippetTypeChange: (SnippetType) -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = { SnippetTypeSelector(snippetType, onSnippetTypeChange) },
        actions = {
            TextButton(onClick = onDiscard, enabled = !saving) { Text("Discard") }
            Button(onClick = onSave, enabled = !saving, modifier = Modifier.padding(end = 8.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
    )
}

@Composable
private fun SnippetTypeSelector(current: SnippetType, onChange: (SnippetType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${current.label} ▾", fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SnippetType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { open = false; onChange(type) })
            }
        }
    }
}
