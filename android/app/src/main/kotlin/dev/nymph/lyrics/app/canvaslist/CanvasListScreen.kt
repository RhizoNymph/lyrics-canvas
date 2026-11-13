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
import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.sync.NotSyncedBadge
import dev.nymph.lyrics.app.sync.SyncStatusChip
import dev.nymph.lyrics.app.ui.NEEDS_CONNECTION
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.dataset.VoiceDatasetDialog
import dev.nymph.lyrics.app.voice.dataset.VoiceDatasetViewModel
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.app.canvaslist.search.GlobalSearchField
import dev.nymph.lyrics.app.canvaslist.search.GlobalSearchResults
import dev.nymph.lyrics.app.canvaslist.search.GlobalSearchResultsView
import dev.nymph.lyrics.app.canvaslist.search.GlobalSearchViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasListScreen(
    container: AppContainer,
    onOpen: (CanvasId) -> Unit,
    onSignOut: () -> Unit,
    /** A snippet search hit: open [CanvasId] focused on [SnippetId]. */
    onOpenSnippet: (CanvasId, SnippetId) -> Unit = { canvas, _ -> onOpen(canvas) },
) {
    val vm: CanvasListViewModel = viewModel { CanvasListViewModel(container.canvases, container.canvasUpdates) }
    val searchVm: GlobalSearchViewModel = viewModel { GlobalSearchViewModel(container.search) }
    val search by searchVm.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val rename by vm.rename.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val dataset: VoiceDatasetViewModel = viewModel { VoiceDatasetViewModel(container.voice) }
    val datasetState by dataset.state.collectAsStateWithLifecycle()
    val syncUi = LocalSyncUi.current
    val unconfirmed by container.syncCenter.unconfirmed.collectAsStateWithLifecycle(initialValue = 0)
    var confirmSignOut by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Canvas?>(null) }

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
                    SyncStatusChip()
                    IconButton(onClick = {
                        container.syncCenter.syncNow()
                        vm.refresh()
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    // Signing out deletes the local copy: say so when it holds unsynced changes.
                    TextButton(onClick = { if (unconfirmed > 0) confirmSignOut = true else onSignOut() }) { Text("Sign out") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (syncUi.online) "Voice dataset" else "Voice dataset ($NEEDS_CONNECTION)") },
                                enabled = syncUi.online,
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            GlobalSearchField(search.text, onEdit = searchVm::edit, onClear = searchVm::clear)
            if (search.results != GlobalSearchResults.Off) {
                GlobalSearchResultsView(search.results, onOpenCanvas = onOpen, onOpenSnippet = onOpenSnippet, onRetry = searchVm::retry)
            } else {
                CanvasListBody(state, vm, onOpen, onDelete = { deleting = it })
            }
        }
    }

    VoiceDatasetDialog(datasetState, onRetry = dataset::open, onDismiss = dataset::close)
    RenameCanvasDialog(rename, onSubmit = vm::submitRename, onDismiss = vm::dismissRename)

    deleting?.let { canvas ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${canvas.title}\"?") },
            text = { Text("The canvas and the snippets that are only on it are deleted. Snippets also on other canvases stay there.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.delete(canvas)
                }) { Text("Delete", color = Palette.Error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("$unconfirmed ${if (unconfirmed == 1) "change has" else "changes have"} not reached the server yet. Signing out deletes them from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Sign out anyway", color = Palette.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

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

/** One canvas: tap opens it; long-press or ⋮ → Rename; ⋮ → Delete. A parked title shows "Not synced". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasRow(canvas: Canvas, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val notSynced = LocalSyncUi.current.isNotSynced(canvas.id)
    ListItem(
        headlineContent = { Text(canvas.title) },
        overlineContent = if (notSynced) ({ NotSyncedBadge() }) else null,
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
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Palette.CanvasBackground),
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onRename, onLongClickLabel = "Rename"),
    )
}

/** The canvas list itself (no search query). */
@Composable
private fun CanvasListBody(state: CanvasListUiState, vm: CanvasListViewModel, onOpen: (CanvasId) -> Unit, onDelete: (Canvas) -> Unit) {
    Box(Modifier.fillMaxSize()) {
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
                        CanvasRow(canvas, onOpen = { onOpen(canvas.id) }, onRename = { vm.startRename(canvas) }, onDelete = { onDelete(canvas) })
                        HorizontalDivider(color = Palette.Border)
                    }
                }
            }
        }
    }
}
