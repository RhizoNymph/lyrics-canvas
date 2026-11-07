package dev.nymph.lyrics.app.voice.check

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.recordings.formatDuration
import dev.nymph.lyrics.app.voice.recordings.formatRecordingDate
import dev.nymph.lyrics.app.voice.userMessage
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.voice.RecordingId
import java.time.ZoneId

/**
 * Full-screen Check transcript for one recording: player, the Whisper reference (tappable words
 * with timings), and the correction field with Save as checked / Mark unchecked / Cancel. Back is
 * Cancel (asks first when the field has edits); leaving the foreground pauses playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckTranscriptScreen(container: AppContainer, snippetId: SnippetId, recordingId: RecordingId, onClose: () -> Unit) {
    val vm: CheckTranscriptViewModel = viewModel {
        CheckTranscriptViewModel(
            snippetId = snippetId,
            recordingId = recordingId,
            voice = container.voice,
            cacheDir = container.recordingsCacheDir,
            player = container.newAudioPlayer(),
            updates = container.recordingUpdates,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                CheckTranscriptEvent.Close -> currentOnClose()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    BackHandler { vm.cancel() }

    Scaffold(
        containerColor = Palette.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text("Check transcript") },
                navigationIcon = { IconButton(onClick = vm::cancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val s = state) {
                CheckTranscriptUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CheckTranscriptUiState.LoadFailed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.error.userMessage(), color = Palette.Error)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = vm::cancel) { Text("Close") }
                        Button(onClick = vm::retryLoad) { Text("Retry") }
                    }
                }
                is CheckTranscriptUiState.Ready -> ReadyContent(s, vm)
            }
        }
    }

    val ready = state as? CheckTranscriptUiState.Ready
    if (ready?.confirmDiscard == true) {
        AlertDialog(
            onDismissRequest = vm::keepEditing,
            title = { Text("Discard your edits?") },
            text = { Text("The correction is not saved. The recording stays as it was.") },
            confirmButton = { TextButton(onClick = vm::confirmDiscard) { Text("Discard", color = Palette.Error) } },
            dismissButton = { TextButton(onClick = vm::keepEditing) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ReadyContent(s: CheckTranscriptUiState.Ready, vm: CheckTranscriptViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${formatRecordingDate(s.recording.createdAt, ZoneId.systemDefault())} · ${formatDuration(s.recording.durationMs)}" +
                if (s.checked) " · Checked" else " · Not checked",
            style = MaterialTheme.typography.labelMedium,
            color = if (s.checked) Palette.Fits else Palette.Muted,
        )
        CheckPlayerBar(s.player, onToggle = vm::togglePlay, onSeek = vm::seekTo)
        TranscriptReferenceView(s.reference, s.currentWord, onWord = vm::seekToWord)
        HorizontalDivider(color = Palette.Border)
        OutlinedTextField(
            value = s.text,
            onValueChange = vm::editText,
            label = { Text("What was said") },
            minLines = 4,
            enabled = !s.busy,
            isError = s.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(CORRECTION_HINT, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        s.error?.let { Text(it.userMessage(), color = Palette.Error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::cancel, enabled = !s.busy) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            if (s.checked) {
                OutlinedButton(onClick = vm::markUnchecked, enabled = !s.busy) {
                    if (s.action == CheckAction.CLEARING) Busy() else Text("Mark unchecked")
                }
            }
            Button(onClick = vm::save, enabled = !s.busy) {
                if (s.action == CheckAction.SAVING) Busy() else Text("Save as checked")
            }
        }
    }
}

@Composable
private fun Busy() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
