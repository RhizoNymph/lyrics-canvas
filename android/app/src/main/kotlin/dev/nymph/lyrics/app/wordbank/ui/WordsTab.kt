package dev.nymph.lyrics.app.wordbank.ui

import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.sync.NotSyncedBadge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.tags.ui.Hint
import dev.nymph.lyrics.app.tags.ui.TagPill
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.wordbank.AddBoxState
import dev.nymph.lyrics.app.wordbank.RenameSession
import dev.nymph.lyrics.app.wordbank.WordBankUiState
import dev.nymph.lyrics.app.wordbank.WordBankViewModel
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.UserTag

/** The add box, then "N words (M shown)" and the filtered list (checkbox or long press to select, inline rename). */
@Composable
internal fun WordsTab(state: WordBankUiState, vm: WordBankViewModel, tags: List<UserTag>, modifier: Modifier = Modifier) {
    Column(modifier) {
        AddBox(state.add, tags, vm)
        HorizontalDivider(color = Palette.Border)
        Text(state.countLabel, color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        val visible = state.visible
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Hint(if (state.allWords.isEmpty()) "Your word bank is empty. Add words above, from rhyme results or from a snippet." else "No words match the filter.")
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.id.value }) { word ->
                WordRow(
                    word = word,
                    selected = word.id in state.selection,
                    rename = state.rename?.takeIf { it.id == word.id },
                    vm = vm,
                )
                HorizontalDivider(color = Palette.RowDivider)
            }
        }
    }
}

/** Text input (commas and newlines split), the tag chips its words get, and Add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBox(add: AddBoxState, tags: List<UserTag>, vm: WordBankViewModel) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = add.text,
                onValueChange = vm::setAddText,
                label = { Text("Add words (comma or new line between)") },
                isError = add.error != null,
                enabled = !add.busy,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submitAdd() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = vm::submitAdd, enabled = !add.busy && add.text.isNotBlank()) { Text(if (add.busy) "Adding…" else "Add") }
        }
        add.error?.let { Hint(it, Palette.Error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val chosen = tags.filter { it.id in add.tagIds }
            chosen.forEach { tag -> TagPill(tag.name + " ✕", tag.color, onClick = { vm.toggleAddTag(tag.id) }) }
            AddTagMenu(tags, add, vm)
        }
    }
}

@Composable
private fun AddTagMenu(tags: List<UserTag>, add: AddBoxState, vm: WordBankViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(if (add.tagIds.isEmpty()) "Tag new words ▾" else "Tags ▾", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (tags.isEmpty()) DropdownMenuItem(text = { Text("No tags yet", color = Palette.Muted) }, onClick = {}, enabled = false)
            tags.forEach { tag ->
                DropdownMenuItem(
                    text = { TagPill(tag.name, tag.color) },
                    leadingIcon = { Checkbox(checked = tag.id in add.tagIds, onCheckedChange = null) },
                    onClick = { vm.toggleAddTag(tag.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun WordRow(word: BankWord, selected: Boolean, rename: RenameSession?, vm: WordBankViewModel) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { vm.toggleSelected(word.id) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.select(word.id)
                },
            )
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { vm.toggleSelected(word.id) })
        if (rename != null) {
            RenameField(rename, vm, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(word.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                if (LocalSyncUi.current.isNotSynced(word.id)) NotSyncedBadge()
                if (word.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        word.tags.forEach { TagPill(it.name, it.color) }
                    }
                }
            }
            IconButton(onClick = { vm.startRename(word.id) }) { Icon(Icons.Default.Edit, contentDescription = "Rename ${word.text}") }
        }
    }
}

@Composable
private fun RenameField(rename: RenameSession, vm: WordBankViewModel, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rename.text,
                onValueChange = vm::setRenameText,
                singleLine = true,
                isError = rename.error != null,
                enabled = !rename.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submitRename() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::submitRename, enabled = !rename.busy) { Icon(Icons.Default.Check, contentDescription = "Save") }
            IconButton(onClick = vm::cancelRename) { Icon(Icons.Default.Close, contentDescription = "Cancel rename") }
        }
        rename.error?.let { Hint(it, Palette.Error) }
    }
}
