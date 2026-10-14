package dev.nymph.lyrics.app.canvas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.canvas.gesture.CanvasGestureAction
import dev.nymph.lyrics.app.canvas.list.SnippetList
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.di.ProcessScope
import dev.nymph.lyrics.app.rhyme.RhymeViewModel
import dev.nymph.lyrics.app.rhyme.ui.RhymePanel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import kotlinx.coroutines.launch

/** Window width from which the rhyme sheet becomes a permanent side pane (expanded width class). */
private val SIDE_PANE_MIN_WIDTH = 840.dp

/** The web sidebar's width. */
private val SIDE_PANE_WIDTH = 380.dp

/** The canvas ViewModel of a canvas back-stack entry (shared with the editor destination). */
@Composable
fun canvasViewModel(
    container: AppContainer,
    canvasId: CanvasId,
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): CanvasViewModel = viewModel(owner) {
    CanvasViewModel(canvasId, container.canvases, container.snippets, ProcessScope.scope, savedState = createSavedStateHandle())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(container: AppContainer, canvasId: CanvasId, onBack: () -> Unit, onOpenEditor: (SnippetId) -> Unit) {
    val vm = canvasViewModel(container, canvasId)
    val rhymeVm: RhymeViewModel = viewModel { RhymeViewModel(vm, container.nlp, container.snippets) }
    val state by vm.state.collectAsStateWithLifecycle()
    val rhyme by rhymeVm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is CanvasEvent.OpenEditor -> onOpenEditor(event.snippetId)
                is CanvasEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushViewport() }

    val ready = state as? CanvasUiState.Ready
    val selection = ready?.selection ?: Selection.Idle
    BackHandler(enabled = selection is Selection.Selecting) { vm.exitSelection() }

    val onAction: (CanvasGestureAction) -> Unit = { action ->
        when (action) {
            is CanvasGestureAction.Tap -> vm.onTap(action.hit)
            is CanvasGestureAction.LongPress -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.onLongPress(action.id)
            }
            is CanvasGestureAction.Pan -> vm.panBy(action.delta)
            is CanvasGestureAction.MoveCard -> vm.moveCardBy(action.id, action.screenDelta)
            is CanvasGestureAction.EndMove -> vm.endMove(action.id)
            is CanvasGestureAction.Transform -> vm.transform(action.centroid, action.pan, action.zoom)
        }
    }
    val toggleRhymes = { if (rhyme.open) rhymeVm.close() else rhymeVm.open() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidePane = maxWidth >= SIDE_PANE_MIN_WIDTH
        BackHandler(enabled = sidePane && rhyme.open && selection is Selection.Idle) { rhymeVm.close() }
        Scaffold(
            containerColor = Palette.CanvasBackground,
            topBar = {
                if (selection is Selection.Selecting) {
                    SelectionTopBar(
                        count = selection.ids.size,
                        busy = ready?.busy == true,
                        onExit = vm::exitSelection,
                        onMerge = vm::merge,
                        onDelete = { confirmDelete = true },
                        onRhymes = rhymeVm::open,
                    )
                } else {
                    CanvasTopBar(
                        title = ready?.title ?: "",
                        mode = ready?.mode?.kind,
                        rhymesOpen = rhyme.open,
                        onBack = onBack,
                        onSetMode = vm::setMode,
                        onToggleRhymes = toggleRhymes,
                    )
                }
            },
            floatingActionButton = {
                if (ready != null && selection is Selection.Idle) {
                    ExtendedFloatingActionButton(
                        onClick = vm::createSnippetAtCenter,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("New snippet") },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val s = state) {
                        CanvasUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is CanvasUiState.Failed -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(s.message, color = Palette.Error)
                            Button(onClick = vm::reload) { Text("Retry") }
                        }
                        is CanvasUiState.Ready -> when (s.mode) {
                            CanvasMode.Canvas -> SnippetCanvas(
                                state = s,
                                hitTest = vm::hitTest,
                                onAction = onAction,
                                onViewSize = vm::setViewSize,
                            )
                            is CanvasMode.SnippetList -> SnippetList(
                                rows = s.listRows,
                                selected = s.selection.ids,
                                onTap = { id -> vm.onTap(id) },
                                onLongPress = vm::onListLongPress,
                            )
                        }
                    }
                }
                if (sidePane && rhyme.open) {
                    VerticalDivider(color = Palette.Border)
                    RhymePanel(rhyme, rhymeVm, onClose = rhymeVm::close, modifier = Modifier.width(SIDE_PANE_WIDTH).fillMaxHeight())
                }
            }
        }
        if (!sidePane && rhyme.open) {
            ModalBottomSheet(
                onDismissRequest = rhymeVm::close,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = Palette.Sidebar,
            ) {
                RhymePanel(rhyme, rhymeVm, onClose = rhymeVm::close, modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (confirmDelete) {
        val count = selection.ids.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (count == 1) "Delete snippet?" else "Delete $count snippets?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("Delete", color = Palette.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
