package dev.nymph.lyrics.app.tags.ui

import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.sync.NotSyncedBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.TagEditor
import dev.nymph.lyrics.app.tags.TagsUiState
import dev.nymph.lyrics.app.tags.TagControlsViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.tags.TAG_COLORS
import dev.nymph.lyrics.logic.tags.TagCheck

private fun TagCheck.toggleable(): ToggleableState = when (this) {
    TagCheck.NONE -> ToggleableState.Off
    TagCheck.SOME -> ToggleableState.Indeterminate
    TagCheck.ALL -> ToggleableState.On
}

/**
 * The tag picker for the items in `state.picker` (shown while it is set): a searchable
 * checklist (mixed state across several items), "Create '<query>'", and per-tag edit / delete.
 * Toggles link or unlink at once. Hosted by the canvas screen, the editor and the word bank;
 * [pluralNoun] names several picked items ("snippets", "words").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(state: TagsUiState<*>, vm: TagControlsViewModel<*>, pluralNoun: String = "snippets") {
    val picker = state.picker ?: return
    ModalBottomSheet(
        onDismissRequest = vm::closePicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Sidebar,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val count = picker.ids.size
            Text(if (count == 1) "Tags" else "Tags for $count $pluralNoun", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = picker.query,
                onValueChange = vm::setPickerQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search or create a tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.createOffer?.let { name ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !picker.creating, onClick = vm::createTagFromPicker).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(if (picker.creating) "Creating '$name'…" else "Create '$name'")
                }
            }
            picker.error?.let { Hint(it, Palette.Error) }
            val rows = state.pickerRows
            if (rows.isEmpty() && state.createOffer == null) Hint(if (state.tags.isEmpty()) "No tags yet. Type a name to create one." else "No matching tags.")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows, key = { it.tag.id.value }) { row ->
                    val pending = row.tag.id in picker.pending
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !pending) { vm.togglePickerTag(row.tag.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = row.check.toggleable(), onClick = { vm.togglePickerTag(row.tag.id) }, enabled = !pending)
                        TagPill(row.tag.name, row.tag.color, Modifier.weight(1f, fill = false))
                        if (LocalSyncUi.current.isNotSynced(row.tag.id)) NotSyncedBadge(Modifier.padding(start = 6.dp))
                        Box(Modifier.weight(1f))
                        IconButton(onClick = { vm.startEditTag(row.tag.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${row.tag.name}") }
                    }
                }
            }
        }
    }
    state.tagEditor?.let { TagEditorDialog(it, vm) }
}

/** Rename and recolour a tag, or delete it (after a confirm). A taken name shows inline. */
@Composable
private fun TagEditorDialog(editor: TagEditor, vm: TagControlsViewModel<*>) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = vm::cancelTagEditor,
        title = { Text("Edit tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = vm::setTagEditorName,
                    label = { Text("Name") },
                    singleLine = true,
                    isError = editor.error != null,
                    enabled = !editor.busy,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TAG_COLORS) { hex ->
                        val chosen = hex.equals(editor.color, ignoreCase = true)
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(Palette.tag(hex), CircleShape)
                                .border(if (chosen) 3.dp else 0.dp, if (chosen) Color.White else Color.Transparent, CircleShape)
                                .clickable(enabled = !editor.busy) { vm.setTagEditorColor(hex) },
                        )
                    }
                }
                editor.error?.let { Hint(it, Palette.Error) }
                Hint("Renaming also updates your presets. Deleting removes the tag from every snippet on every canvas and from every word bank word.")
            }
        },
        confirmButton = { TextButton(onClick = vm::saveTagEditor, enabled = !editor.busy) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }, enabled = !editor.busy) { Text("Delete", color = Palette.Error) }
                TextButton(onClick = vm::cancelTagEditor) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete \"${editor.name}\"?",
            text = "It is removed from every snippet on every canvas and from your word bank. Presets keep the name, which then matches nothing.",
            confirm = "Delete",
            onConfirm = { vm.deleteTag(editor.tagId) },
            onDismiss = { confirmDelete = false },
        )
    }
}
