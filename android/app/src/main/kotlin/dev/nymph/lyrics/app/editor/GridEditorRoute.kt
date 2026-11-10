package dev.nymph.lyrics.app.editor

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.editor.ui.AlsoOnLine
import dev.nymph.lyrics.app.editor.ui.GridEditorScreen
import dev.nymph.lyrics.app.editor.ui.LocalAddToWordBank
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.app.voice.recordings.RecordingsSection
import dev.nymph.lyrics.app.voice.recordings.RecordingsViewModel
import dev.nymph.lyrics.app.voice.ui.VoiceCaptureHost
import dev.nymph.lyrics.app.voice.ui.voiceCaptureViewModel
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingId
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Entry point of the full-screen grid editor. The canvas screen navigates here with the snippet
 * to edit and receives the saved snippet through [onSaved] (or nothing via [onClose], which is
 * also Discard). This signature is the contract between the canvas and editor workstreams.
 *
 * Voice takes: Record saves pending edits first, then opens the recording modal targeting this
 * snippet (append); the returned snippet replaces the editor rows and reaches the canvas through
 * [onSnippetChanged] without closing the editor. The recordings section lists its takes.
 */
@Composable
fun GridEditorRoute(
    container: AppContainer,
    snippet: Snippet,
    onSaved: (Snippet) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSnippetChanged: (Snippet) -> Unit = {},
    /** More top-bar actions from the host (the tag picker's "Tags"). */
    extraTopBarActions: @Composable RowScope.() -> Unit = {},
    /** "Add to word bank" on a line field's text selection; null hides it. */
    onAddToWordBank: ((String) -> Unit)? = null,
    /** A recording's "Check transcript" (the host navigates to the check screen). */
    onCheckTranscript: (RecordingId) -> Unit = {},
    /** Opens another canvas from the "Also on: …" line; null leaves the names as plain text. */
    onOpenCanvas: ((CanvasId) -> Unit)? = null,
) {
    // One ViewModel per visit: survives rotation (saveable key), fresh when the editor is reopened,
    // even if the host keeps one ViewModelStore across visits.
    val visit = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel = viewModel(key = "grid-editor-${snippet.id.value}-$visit") {
        GridEditorViewModel(snippet, container.nlp, container.snippets)
    }
    val voice = voiceCaptureViewModel(container, snippet.canvasId, key = "voice-editor-${snippet.id.value}-$visit")
    val recordings = viewModel(key = "recordings-${snippet.id.value}-$visit") {
        RecordingsViewModel(
            snippet.id,
            container.voice,
            container.recordingsCacheDir,
            container.newAudioPlayer(),
            container.recordingUpdates.updates,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordingsState by recordings.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnChanged by rememberUpdatedState(onSnippetChanged)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var flushing by remember { mutableStateOf(false) }

    val save = state.save
    LaunchedEffect(save) {
        if (save is SaveStatus.Saved) currentOnSaved(save.snippet)
    }
    LaunchedEffect(voice) {
        voice.saved.collect { saved ->
            viewModel.reloadFrom(saved.response.snippet)
            recordings.add(saved.response.recording)
            currentOnChanged(saved.response.snippet)
        }
    }
    LaunchedEffect(recordings) { recordings.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recordings.stopPlayback() }

    val record: () -> Unit = {
        if (!flushing) {
            flushing = true
            recordings.stopPlayback()
            scope.launch {
                when (val flushed = viewModel.flushPendingEdits()) {
                    is ApiResult.Success -> {
                        currentOnChanged(flushed.value)
                        voice.open(VoiceTakeTarget.Append(snippet.id))
                    }
                    is ApiResult.Failure -> snackbar.showSnackbar("Save your edits first: ${flushed.error.userMessage()}")
                }
                flushing = false
            }
        }
    }

    CompositionLocalProvider(LocalAddToWordBank provides onAddToWordBank) {
        GridEditorScreen(
            state = state,
            actions = viewModel,
            onDiscard = onClose,
            modifier = modifier,
            snackbar = snackbar,
            topBarActions = {
                extraTopBarActions()
                TextButton(onClick = record, enabled = !flushing && !state.isSaving) { Text("Record") }
            },
            header = { AlsoOnLine(snippet, onOpenCanvas) },
            bottomPanel = {
                RecordingsSection(recordingsState, recordings, onCheckTranscript = { recording ->
                    recordings.stopPlayback()
                    onCheckTranscript(recording.id)
                })
            },
        )
    }
    VoiceCaptureHost(voice)
}
