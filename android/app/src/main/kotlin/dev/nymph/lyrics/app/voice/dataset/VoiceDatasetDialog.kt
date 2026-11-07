package dev.nymph.lyrics.app.voice.dataset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.userMessage

/** The "Voice dataset" info dialog: stats only, and a pointer to the web app for the export. */
@Composable
fun VoiceDatasetDialog(state: DatasetStatsState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (state == DatasetStatsState.Closed) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice dataset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    DatasetStatsState.Closed -> Unit
                    DatasetStatsState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is DatasetStatsState.Loaded -> Text(datasetSummary(state.stats))
                    is DatasetStatsState.Failed -> Text(state.error.userMessage(), color = Palette.Error)
                }
                Text(EXPORT_ON_WEB, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (state is DatasetStatsState.Failed) {
            { TextButton(onClick = onRetry) { Text("Retry") } }
        } else {
            null
        },
    )
}
