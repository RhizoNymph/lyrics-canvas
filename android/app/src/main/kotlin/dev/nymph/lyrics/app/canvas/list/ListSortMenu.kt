package dev.nymph.lyrics.app.canvas.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.list.ListSort

/**
 * The list-mode sort row: "Sort: <current> ▾" opening Last edited / Created / A-Z / Custom (the
 * current one checked). Dragging a row by its handle reorders into Custom.
 */
@Composable
fun ListSortBar(sort: ListSort, onSort: (ListSort) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().background(Palette.CanvasBackground).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort:", color = Palette.Muted, modifier = Modifier.padding(start = 8.dp))
        Box {
            TextButton(onClick = { open = true }) { Text("${sort.label} ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ListSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            if (option == sort) Icon(Icons.Default.Check, contentDescription = "Current sort") else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            open = false
                            onSort(option)
                        },
                    )
                }
            }
        }
    }
}
