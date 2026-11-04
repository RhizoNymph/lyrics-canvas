package dev.nymph.lyrics.app.tags.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.PresetEditor
import dev.nymph.lyrics.app.tags.TagsUiState
import dev.nymph.lyrics.app.tags.TagsViewModel
import dev.nymph.lyrics.app.tags.userMessage
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId

/**
 * The canvas filter bar (canvas and list mode): the expression field, clear, and the preset
 * menu (apply, save as, manage). An invalid text is shown as such while the last valid filter
 * keeps applying.
 */
@Composable
fun FilterBar(state: TagsUiState, vm: TagsViewModel, modifier: Modifier = Modifier) {
    // A Column: the bar, then (on a failed load) the retry line.
    Column(modifier) { FilterBarContent(state, vm) }
}

@Composable
private fun FilterBarContent(state: TagsUiState, vm: TagsViewModel) {
    var managing by rememberSaveable { mutableStateOf(false) }
    val bar = state.filterBar
    val note = when {
        !bar.invalid -> null
        bar.appliedText.isBlank() -> "showing every snippet"
        else -> "still showing: ${bar.appliedText.trim()}"
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.Sidebar).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ExpressionInput(
            field = bar.input,
            tags = state.tags,
            label = "Filter by tags",
            onEdit = vm::editFilter,
            onAccept = vm::acceptFilterSuggestion,
            onInsertTag = vm::insertFilterTag,
            note = note,
            trailingIcon = if (bar.input.text.isNotEmpty()) {
                { IconButton(onClick = vm::clearFilter) { Icon(Icons.Default.Close, contentDescription = "Clear filter") } }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        PresetMenu(
            presets = state.presets,
            onApply = { vm.applyPreset(it.id) },
            actions = listOf(
                MenuAction("Save filter as preset…", vm::startSaveAsPreset),
                MenuAction("Manage presets…") { managing = true },
            ),
        )
    }
    state.loadError?.let { error ->
        Row(Modifier.fillMaxWidth().background(Palette.Sidebar).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint("Couldn't load tags and presets: ${error.userMessage()}", Palette.Error)
            TextButton(onClick = vm::reload) { Text("Retry", fontSize = 12.sp) }
        }
    }
    state.presetEditor?.let { PresetNameDialog(it, vm) }
    if (managing) ManagePresetsDialog(state.presets, vm, onDismiss = { managing = false })
}

/** The name prompt for "Save as preset" and rename; a taken name shows inline. */
@Composable
fun PresetNameDialog(editor: PresetEditor, vm: TagsViewModel) {
    val saveAs = editor.target is PresetEditor.Target.SaveAs
    AlertDialog(
        onDismissRequest = vm::cancelPresetEditor,
        title = { Text(if (saveAs) "Save filter as preset" else "Rename preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = vm::setPresetName,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = editor.error != null,
                    enabled = !editor.busy,
                )
                editor.error?.let { Hint(it, Palette.Error) }
            }
        },
        confirmButton = { TextButton(onClick = vm::submitPresetEditor, enabled = !editor.busy) { Text(if (saveAs) "Save" else "Rename") } },
        dismissButton = { TextButton(onClick = vm::cancelPresetEditor) { Text("Cancel") } },
    )
}

/** Rename, overwrite (with the current filter) or delete each preset. */
@Composable
private fun ManagePresetsDialog(presets: List<TagPreset>, vm: TagsViewModel, onDismiss: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmOverwrite by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
        text = {
            if (presets.isEmpty()) {
                Hint("No presets yet. Type a filter, then use Presets ▾ › Save filter as preset.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(presets, key = { it.id.value }) { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(preset.expression, color = Palette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.startRenamePreset(preset.id) }) { Icon(Icons.Default.Edit, contentDescription = "Rename ${preset.name}") }
                            TextButton(onClick = { confirmOverwrite = preset.id.value }) { Text("Overwrite", fontSize = 12.sp) }
                            IconButton(onClick = { confirmDelete = preset.id.value }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${preset.name}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    confirmOverwrite?.let { id ->
        val preset = presets.firstOrNull { it.id.value == id }
        ConfirmDialog(
            title = "Overwrite \"${preset?.name ?: ""}\"?",
            text = "Its expression becomes the current filter.",
            confirm = "Overwrite",
            onConfirm = { vm.overwritePreset(TagPresetId(id)) },
            onDismiss = { confirmOverwrite = null },
        )
    }
    confirmDelete?.let { id ->
        val preset = presets.firstOrNull { it.id.value == id }
        ConfirmDialog(
            title = "Delete \"${preset?.name ?: ""}\"?",
            text = "This cannot be undone.",
            confirm = "Delete",
            onConfirm = { vm.deletePreset(TagPresetId(id)) },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm, color = Palette.Error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
