package dev.nymph.lyrics.app.voice.check

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.recordings.formatDuration

/** Play / pause, a scrubber and `position / duration`; the scrubber seeks when released. */
@Composable
fun CheckPlayerBar(player: PlayerUi, onToggle: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (player) {
            PlayerUi.Loading -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading audio…", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            is PlayerUi.Failed -> Text(player.failure.userMessage(), color = Palette.Error, style = MaterialTheme.typography.bodySmall)
            is PlayerUi.Ready -> ReadyBar(player, onToggle, onSeek)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ReadyBar(player: PlayerUi.Ready, onToggle: () -> Unit, onSeek: (Long) -> Unit) {
    // While the thumb is dragged it shows the drag position, not the player's.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1)
    val shown = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    FilledTonalButton(onClick = onToggle) { Text(if (player.playing) "Pause" else "Play") }
    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek((it * duration).toLong()) }
            dragging = null
        },
        modifier = Modifier.weight(1f),
    )
    Text(
        "${formatDuration((shown * duration).toLong())} / ${formatDuration(player.durationMs)}",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = Palette.FallbackText,
    )
}
