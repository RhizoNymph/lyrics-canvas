package dev.nymph.lyrics.app.wordbank.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nymph.lyrics.app.tags.TagControlsViewModel
import dev.nymph.lyrics.app.tags.ui.FilterBar
import dev.nymph.lyrics.app.tags.ui.Hint
import dev.nymph.lyrics.app.tags.ui.TagPickerSheet
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.wordbank.DraftState
import dev.nymph.lyrics.app.wordbank.Remote
import dev.nymph.lyrics.app.wordbank.WordBankTab
import dev.nymph.lyrics.app.wordbank.WordBankViewModel
import dev.nymph.lyrics.app.wordbank.userMessage
import dev.nymph.lyrics.logic.wordbank.needsDeleteConfirm
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.SnippetType
import kotlinx.coroutines.launch

/**
 * The full-screen word bank of a canvas: filter bar (its own remembered filter, the shared presets),
 * tabs Words / Rhymes / Related, a selection bar (Tags, Make snippet, Delete), the tag picker and the
 * snippet draft sheet. Back leaves the selection first, then the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordBankScreen(vm: WordBankViewModel, tagsVm: TagControlsViewModel<BankWordId>, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tags by tagsVm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { text -> scope.launch { snackbar.showSnackbar(text) } } }
    LaunchedEffect(tagsVm) { tagsVm.messages.collect { text -> scope.launch { snackbar.showSnackbar(text) } } }
    BackHandler(enabled = state.selection.isNotEmpty()) { vm.clearSelection() }

    val deleteSelection = { if (needsDeleteConfirm(state.selection.size)) confirmDelete = true else vm.deleteSelected() }

    Scaffold(
        containerColor = Palette.CanvasBackground,
        topBar = {
            if (state.selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${state.selection.size}") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, contentDescription = "Exit selection") } },
                    actions = {
                        TextButton(onClick = { tagsVm.openPicker(state.selection) }) { Text("Tags") }
                        TextButton(onClick = vm::openDraft, enabled = state.canMakeSnippet) { Text("Make snippet") }
                        IconButton(onClick = deleteSelection, enabled = !state.deleting) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Border),
                )
            } else {
                TopAppBar(
                    title = { Text("Word bank") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Sidebar),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterBar(tags, tagsVm, everythingNote = "showing every word")
            TabRow(selectedTabIndex = state.tab.ordinal, containerColor = Palette.Sidebar) {
                WordBankTab.entries.forEach { tab ->
                    Tab(selected = state.tab == tab, onClick = { vm.setTab(tab) }, text = { Text(tab.label) })
                }
            }
            when (val words = state.words) {
                Remote.Loading, Remote.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Remote.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(words.error.userMessage(), color = Palette.Error)
                    Button(onClick = vm::reload) { Text("Retry") }
                }
                is Remote.Loaded -> when (state.tab) {
                    WordBankTab.WORDS -> WordsTab(state, vm, tags.tags, Modifier.fillMaxSize())
                    WordBankTab.RHYMES -> RhymesTab(state, vm, Modifier.fillMaxSize())
                    WordBankTab.RELATED -> RelatedTab(state, vm, Modifier.fillMaxSize())
                }
            }
        }
    }

    TagPickerSheet(tags, tagsVm, pluralNoun = "words")
    state.draft?.let { DraftSheet(it, vm) }

    if (confirmDelete) {
        val count = state.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $count words?") },
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

private val WordBankTab.label: String
    get() = when (this) {
        WordBankTab.WORDS -> "Words"
        WordBankTab.RHYMES -> "Rhymes"
        WordBankTab.RELATED -> "Related"
    }

private val SnippetType.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** The editable draft: the selected words one per line, a type (default freestyle), Create / Cancel. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DraftSheet(draft: DraftState, vm: WordBankViewModel) {
    ModalBottomSheet(
        onDismissRequest = vm::cancelDraft,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Sidebar,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New snippet from words", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = draft.text,
                onValueChange = vm::setDraftText,
                enabled = !draft.busy,
                isError = draft.error != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SnippetType.entries.forEach { type ->
                    FilterChip(selected = draft.type == type, onClick = { vm.setDraftType(type) }, label = { Text(type.label) }, enabled = !draft.busy)
                }
            }
            draft.error?.let { Hint(it, Palette.Error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = vm::cancelDraft) { Text("Cancel") }
                Button(onClick = vm::createDraft, enabled = !draft.busy) { Text(if (draft.busy) "Creating…" else "Create") }
            }
        }
    }
}
