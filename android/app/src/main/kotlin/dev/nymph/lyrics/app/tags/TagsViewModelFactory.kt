package dev.nymph.lyrics.app.tags

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.canvas.CanvasViewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.model.CanvasId

/** The tags ViewModel of a canvas back-stack entry (shared with the editor destination), wired to [canvas]. */
@Composable
fun tagsViewModel(
    container: AppContainer,
    canvasId: CanvasId,
    canvas: CanvasViewModel,
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): TagsViewModel = viewModel(owner) { TagsViewModel(canvasId, container.tags, container.tagPresets, container.tagFilters, canvas) }
