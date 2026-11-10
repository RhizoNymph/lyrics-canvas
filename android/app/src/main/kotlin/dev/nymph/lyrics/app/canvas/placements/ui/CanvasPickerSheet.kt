package dev.nymph.lyrics.app.canvas.placements.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.canvas.placements.CanvasChoices
import dev.nymph.lyrics.app.canvas.placements.TransferUiState
import dev.nymph.lyrics.app.canvas.placements.TransferViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.TransferMode

/** The transfer picker: "New canvas…", a search field and the other canvases, newest updated first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasPickerSheet(state: TransferUiState, vm: TransferViewModel) {
    val open = state as? TransferUiState.Picking ?: return
    val count = open.ids.size
    val noun = if (count == 1) "1 snippet" else "$count snippets"
    ModalBottomSheet(
        onDismissRequest = vm::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Sidebar,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (open.mode) {
                    TransferMode.MOVE -> "Move $noun to…"
                    TransferMode.SHOW -> "Also show $noun on…"
                },
                color = Palette.Text,
            )
            OutlinedTextField(
                value = open.query,
                onValueChange = vm::setQuery,
                label = { Text("Search canvases") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            open.error?.let { Text(it, color = Palette.Error) }
            if (open.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            item(key = "new") {
                ListItem(
                    headlineContent = { Text("New canvas…") },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Palette.Sidebar),
                    modifier = Modifier.clickable(enabled = !open.busy, onClick = vm::startNewCanvas),
                )
                HorizontalDivider(color = Palette.Border)
            }
            when (val choices = open.choices) {
                CanvasChoices.Loading -> item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                is CanvasChoices.Failed -> item(key = "failed") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(choices.message, color = Palette.Error)
                        Button(onClick = vm::retryLoad) { Text("Retry") }
                    }
                }
                is CanvasChoices.Loaded -> {
                    if (open.rows.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (open.query.isBlank()) "No other canvases yet." else "No canvas matches.",
                                color = Palette.Muted,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(open.rows, key = { it.id.value }) { canvas ->
                        ListItem(
                            headlineContent = { Text(canvas.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Updated ${canvas.updatedAt.take(10)}", color = Palette.Muted) },
                            colors = ListItemDefaults.colors(containerColor = Palette.Sidebar),
                            modifier = Modifier.clickable(enabled = !open.busy) { vm.choose(canvas) },
                        )
                    }
                }
            }
        }
    }

    open.newCanvas?.let { prompt ->
        var title by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!open.busy) vm.dismissNewCanvas() },
            title = { Text("New canvas") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !open.busy,
                    isError = prompt.error != null,
                    supportingText = prompt.error?.let { message -> { Text(message, color = Palette.Error) } },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.submitNewCanvas(title) }, enabled = !open.busy) {
                    Text(if (open.mode == TransferMode.MOVE) "Create and move" else "Create and show")
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissNewCanvas, enabled = !open.busy) { Text("Cancel") } },
        )
    }
}
