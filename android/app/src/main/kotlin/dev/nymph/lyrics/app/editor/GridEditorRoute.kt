package dev.nymph.lyrics.app.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.model.Snippet

/**
 * Entry point of the full-screen grid editor. The canvas screen navigates here with the snippet
 * to edit and receives the saved snippet through [onSaved] (or nothing via [onClose]).
 * This signature is the contract between the canvas and editor workstreams; the body is a stub
 * until the editor lands.
 */
@Composable
fun GridEditorRoute(
    container: AppContainer,
    snippet: Snippet,
    onSaved: (Snippet) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Editor for ${snippet.id.value}")
    }
}
