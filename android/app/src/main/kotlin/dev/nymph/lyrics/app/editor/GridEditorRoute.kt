package dev.nymph.lyrics.app.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.editor.ui.GridEditorScreen
import dev.nymph.lyrics.model.Snippet
import java.util.UUID

/**
 * Entry point of the full-screen grid editor. The canvas screen navigates here with the snippet
 * to edit and receives the saved snippet through [onSaved] (or nothing via [onClose], which is
 * also Discard). This signature is the contract between the canvas and editor workstreams.
 */
@Composable
fun GridEditorRoute(
    container: AppContainer,
    snippet: Snippet,
    onSaved: (Snippet) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One ViewModel per visit: survives rotation (saveable key), fresh when the editor is reopened,
    // even if the host keeps one ViewModelStore across visits.
    val visit = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel = viewModel(key = "grid-editor-${snippet.id.value}-$visit") {
        GridEditorViewModel(snippet, container.nlp, container.snippets)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)
    val save = state.save
    LaunchedEffect(save) {
        if (save is SaveStatus.Saved) currentOnSaved(save.snippet)
    }
    GridEditorScreen(state = state, actions = viewModel, onDiscard = onClose, modifier = modifier)
}
