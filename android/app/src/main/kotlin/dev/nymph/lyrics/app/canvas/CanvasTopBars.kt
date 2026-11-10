package dev.nymph.lyrics.app.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.MergeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasTopBar(
    title: String,
    mode: CanvasModeKind?,
    rhymesOpen: Boolean,
    onBack: () -> Unit,
    onSetMode: (CanvasModeKind) -> Unit,
    onToggleRhymes: () -> Unit,
    /** Opens the voice recording modal; null hides the button (not loaded yet). */
    onRecord: (() -> Unit)? = null,
    /** Opens the word bank screen; null hides the button. */
    onWordBank: (() -> Unit)? = null,
    /** Tapping the title opens the rename dialog; null while not loaded. */
    onRename: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            val modifier = if (onRename != null) Modifier.clickable(onClickLabel = "Rename canvas", role = Role.Button, onClick = onRename) else Modifier
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            // Only once loaded: the label names the mode the button switches to.
            when (mode) {
                CanvasModeKind.CANVAS -> TextButton(onClick = { onSetMode(CanvasModeKind.LIST) }) { Text("List") }
                CanvasModeKind.LIST -> TextButton(onClick = { onSetMode(CanvasModeKind.CANVAS) }) { Text("Canvas") }
                null -> Unit
            }
            if (onRecord != null) TextButton(onClick = onRecord) { Text("Record") }
            if (onWordBank != null) TextButton(onClick = onWordBank) { Text("Words") }
            TextButton(onClick = onToggleRhymes) { Text(if (rhymesOpen) "Hide rhymes" else "Rhymes") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Sidebar),
    )
}

/** The selection's placement actions, shown in the selection bar's ⋮ menu. */
class SelectionPlacementActions(
    /** "Delete", or "Remove from this canvas" when a selected snippet is on other canvases. */
    val deleteLabel: String,
    /** Some selected snippet is on other canvases ("On other canvases…" is offered). */
    val onOtherCanvases: Boolean,
    val onMoveTo: () -> Unit,
    val onAlsoShow: () -> Unit,
    val onShowCanvases: () -> Unit,
    val onDeleteEverywhere: () -> Unit,
)

/**
 * The contextual bar while selecting: count, Merge ⇣, Merge ⇢, Tags, Delete (trash; labelled by
 * [SelectionPlacementActions.deleteLabel]), Rhymes, ⋮ (Move to canvas…, Also show on canvas…,
 * On other canvases…, the delete label again, Delete everywhere…), ✕.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    busy: Boolean,
    onExit: () -> Unit,
    onMerge: (MergeMode) -> Unit,
    onDelete: () -> Unit,
    onRhymes: () -> Unit,
    /** Opens the tag picker for the selection. */
    onTags: () -> Unit = {},
    placements: SelectionPlacementActions? = null,
) {
    val canMerge = count >= 2 && !busy
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count") },
        navigationIcon = {
            IconButton(onClick = onExit) { Icon(Icons.Default.Close, contentDescription = "Exit selection") }
        },
        actions = {
            TextButton(onClick = { onMerge(MergeMode.VERTICAL) }, enabled = canMerge) { Text("Merge ⇣") }
            TextButton(onClick = { onMerge(MergeMode.HORIZONTAL) }, enabled = canMerge) { Text("Merge ⇢") }
            TextButton(onClick = onTags, enabled = !busy) { Text("Tags") }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = placements?.deleteLabel ?: "Delete")
            }
            TextButton(onClick = onRhymes) { Text("Rhymes") }
            if (placements != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val item = @Composable { label: String, enabled: Boolean, action: () -> Unit ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                enabled = enabled,
                                onClick = {
                                    menuOpen = false
                                    action()
                                },
                            )
                        }
                        item("Move to canvas…", !busy, placements.onMoveTo)
                        item("Also show on canvas…", !busy, placements.onAlsoShow)
                        if (placements.onOtherCanvases) item("On other canvases…", true, placements.onShowCanvases)
                        HorizontalDivider(color = Palette.Sidebar)
                        item(placements.deleteLabel, !busy, onDelete)
                        item("Delete everywhere…", !busy, placements.onDeleteEverywhere)
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Border),
    )
}
