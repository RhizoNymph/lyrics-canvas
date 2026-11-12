package dev.nymph.lyrics.app.voice.recordings

import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.sync.NeedsConnectionNote
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.voice.Recording
import java.time.ZoneId

/** Rows visible before the section scrolls inside itself. */
private val SECTION_MAX_HEIGHT = 300.dp

/**
 * The editor's collapsible "Recordings" section: one row per take (date, duration, transcript
 * preview, Checked / Not checked badge), Play / Pause, Check transcript, and Delete behind a
 * confirmation dialog.
 */
@Composable
fun RecordingsSection(
    state: RecordingsUiState,
    vm: RecordingsViewModel,
    onCheckTranscript: (Recording) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val list = state.list
    val count = (list as? RecordingsList.Loaded)?.items?.size
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = Palette.Border)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (count == null) "Recordings" else "Recordings ($count)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "▸", color = Palette.Muted)
        }
        if (expanded && !LocalSyncUi.current.online) {
            NeedsConnectionNote("Recordings", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        } else if (expanded) {
            when (list) {
                RecordingsList.Loading -> CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                is RecordingsList.Failed -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(list.message, color = Palette.Error, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh) { Text("Retry") }
                }
                is RecordingsList.Loaded -> if (list.items.isEmpty()) {
                    Text(
                        "No recordings yet. Use Record to dictate lines into this snippet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    val zone = ZoneId.systemDefault()
                    LazyColumn(Modifier.heightIn(max = SECTION_MAX_HEIGHT)) {
                        items(list.items, key = { it.id.value }) { recording ->
                            RecordingRow(
                                recording = recording,
                                zone = zone,
                                playback = state.playback,
                                deleting = recording.id in state.deleting,
                                onPlay = { vm.togglePlay(recording.id) },
                                onDelete = { vm.requestDelete(recording.id) },
                                onCheck = { onCheckTranscript(recording) },
                            )
                        }
                    }
                }
            }
        }
    }
    state.confirmDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text("Delete recording?") },
            text = { Text("The audio from ${formatRecordingDate(pending.createdAt, ZoneId.systemDefault())} is deleted. The snippet's text stays.") },
            confirmButton = { TextButton(onClick = vm::confirmDelete) { Text("Delete", color = Palette.Error) } },
            dismissButton = { TextButton(onClick = vm::dismissDelete) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    zone: ZoneId,
    playback: Playback,
    deleting: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
) {
    val mine = playback.activeId == recording.id
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatRecordingDate(recording.createdAt, zone)} · ${formatDuration(recording.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Muted,
            )
            Text(
                transcriptPreview(recording.transcript),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CheckedBadge(recording.isChecked)
                TextButton(onClick = onCheck, enabled = !deleting) { Text("Check transcript") }
            }
        }
        when {
            mine && playback is Playback.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> TextButton(onClick = onPlay, enabled = !deleting) {
                Text(if (mine && playback is Playback.Playing) "Pause" else if (mine && playback is Playback.Paused) "Resume" else "Play")
            }
        }
        IconButton(onClick = onDelete, enabled = !deleting) { Icon(Icons.Default.Delete, contentDescription = "Delete recording") }
    }
}

/** "Checked" when the take has a checked transcript, "Not checked" otherwise. */
@Composable
private fun CheckedBadge(checked: Boolean) {
    val color = if (checked) Palette.Fits else Palette.Muted
    Text(
        if (checked) "Checked" else "Not checked",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
