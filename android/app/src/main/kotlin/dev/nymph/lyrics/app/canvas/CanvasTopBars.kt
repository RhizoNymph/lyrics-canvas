package dev.nymph.lyrics.app.canvas

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.MergeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasTopBar(title: String, rhymesOpen: Boolean, onBack: () -> Unit, onToggleRhymes: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            TextButton(onClick = onToggleRhymes) { Text(if (rhymesOpen) "Hide rhymes" else "Rhymes") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Sidebar),
    )
}

/** The contextual bar while selecting: count, Merge ⇣, Merge ⇢, Delete, Rhymes, ✕. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    busy: Boolean,
    onExit: () -> Unit,
    onMerge: (MergeMode) -> Unit,
    onDelete: () -> Unit,
    onRhymes: () -> Unit,
) {
    val canMerge = count >= 2 && !busy
    TopAppBar(
        title = { Text("$count") },
        navigationIcon = {
            IconButton(onClick = onExit) { Icon(Icons.Default.Close, contentDescription = "Exit selection") }
        },
        actions = {
            TextButton(onClick = { onMerge(MergeMode.VERTICAL) }, enabled = canMerge) { Text("Merge ⇣") }
            TextButton(onClick = { onMerge(MergeMode.HORIZONTAL) }, enabled = canMerge) { Text("Merge ⇢") }
            IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            TextButton(onClick = onRhymes) { Text("Rhymes") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Border),
    )
}
