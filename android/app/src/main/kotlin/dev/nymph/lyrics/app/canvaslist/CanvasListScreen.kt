package dev.nymph.lyrics.app.canvaslist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.canvas.rename.RenameCanvasDialog
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.dataset.VoiceDatasetDialog
import dev.nymph.lyrics.app.voice.dataset.VoiceDatasetViewModel
import dev.nymph.lyrics.model.CanvasId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasListScreen(container: AppContainer, onOpen: (CanvasId) -> Unit, onSignOut: () -> Unit) {
    val vm: CanvasListViewModel = viewModel { CanvasListViewModel(container.canvases, container.canvasUpdates) }
    val state by vm.state.collectAsStateWithLifecycle()
    val rename by vm.rename.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val dataset: VoiceDatasetViewModel = viewModel { VoiceDatasetViewModel(container.voice) }
    val datasetState by dataset.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is CanvasListEvent.Open -> onOpen(event.id)
                is CanvasListEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    Scaffold(
        containerColor = Palette.CanvasBackground,
        topBar = {
            TopAppBar(
                title = { Text("Canvases") },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Voice dataset") },
                                onClick = {
                                    menuOpen = false
                                    dataset.open()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New canvas") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                CanvasListUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CanvasListUiState.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.message, color = Palette.Error)
                    Button(onClick = vm::refresh) { Text("Retry") }
                }
                is CanvasListUiState.Ready -> if (s.canvases.isEmpty()) {
                    Text("No canvases yet. Create one to start writing.", color = Palette.Muted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.canvases, key = { it.id.value }) { canvas ->
                            CanvasRow(canvas, onOpen = { onOpen(canvas.id) }, onRename = { vm.startRename(canvas) })
                            HorizontalDivider(color = Palette.Border)
                        }
                    }
                }
            }
        }
    }

    VoiceDatasetDialog(datasetState, onRetry = dataset::open, onDismiss = dataset::close)
    RenameCanvasDialog(rename, onSubmit = vm::submitRename, onDismiss = vm::dismissRename)

    if (creating) {
        var title by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New canvas") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    creating = false
                    vm.create(title)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/** One canvas: tap opens it; long-press or ⋮ → Rename. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasRow(canvas: Canvas, onOpen: () -> Unit, onRename: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(canvas.title) },
        supportingContent = { Text("Updated ${canvas.updatedAt.take(10)}", color = Palette.Muted) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Canvas actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Palette.CanvasBackground),
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onRename, onLongClickLabel = "Rename"),
    )
}
