package dev.nymph.lyrics.app.canvas.placements.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nymph.lyrics.app.canvas.placements.confirmText
import dev.nymph.lyrics.app.canvas.placements.confirmTitle
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.placements.DeleteEverywherePlan
import dev.nymph.lyrics.logic.placements.RemovalPlan
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef

/** Confirm a plain delete that deletes some snippets for good ([RemovalPlan.needsConfirm]). */
@Composable
fun RemovalConfirmDialog(plan: RemovalPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(plan.confirmTitle()) },
        text = { Text(plan.confirmText()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (plan.removesShared) "Remove" else "Delete", color = Palette.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirm Delete everywhere, naming the other canvases the snippets disappear from. */
@Composable
fun DeleteEverywhereDialog(plan: DeleteEverywherePlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(plan.confirmTitle()) },
        text = { Text(plan.confirmText()) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete everywhere", color = Palette.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The badge's list: the other canvases holding the selection; tapping one opens it. */
@Composable
fun OtherCanvasesDialog(canvases: List<CanvasRef>, onOpen: (CanvasId) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Also on") },
        text = {
            Column {
                for (canvas in canvases) {
                    ListItem(
                        headlineContent = { Text(canvas.title) },
                        colors = ListItemDefaults.colors(containerColor = Palette.Sidebar),
                        modifier = Modifier.clickable { onOpen(canvas.id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Palette.Sidebar,
    )
}
