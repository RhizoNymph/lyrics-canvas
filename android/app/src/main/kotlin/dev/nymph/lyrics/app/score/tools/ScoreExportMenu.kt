package dev.nymph.lyrics.app.score.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.ui.NEEDS_CONNECTION
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.score.ScoreExportFormat
import kotlinx.coroutines.launch
import java.io.File

/**
 * The editor's "Export" top-bar menu for a snippet's score: MIDI or MusicXML, each to the share
 * sheet or saved with the system file picker (SAF). The file comes from the server's stored
 * score, so unsynced edits are waited for (and the menu is disabled offline). [onMessage] shows a
 * snackbar (failures, "Saved"). Hook for feat/android-score-editor: mount it in the editor's top
 * bar while the snippet has a score ([hasScore]).
 */
@Composable
fun ScoreExportMenu(
    container: AppContainer,
    snippetId: SnippetId,
    hasScore: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    key: String = "score-export-${snippetId.value}",
) {
    val vm = viewModel(key = key) { ScoreExportViewModel(snippetId, container.scoreTools, container.scoreExportsDir) }
    ScoreExportMenu(vm, hasScore, onMessage, modifier)
}

@Composable
fun ScoreExportMenu(vm: ScoreExportViewModel, hasScore: Boolean, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by vm.state.collectAsStateWithLifecycle()
    val online = LocalSyncUi.current.online
    val message by rememberUpdatedState(onMessage)
    var open by remember { mutableStateOf(false) }
    // Survives the activity being recreated while the system picker is up.
    var pendingSave by rememberSaveable(stateSaver = ExportedFileSaver) { mutableStateOf<ExportedFile?>(null) }

    val saver = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) {
            scope.launch {
                when (val result = saveExport(context, file, uri)) {
                    FileActionResult.Done -> message("Saved ${file.name}")
                    is FileActionResult.Failed -> message(result.message)
                }
            }
        }
    }

    LaunchedEffect(vm) {
        vm.ready.collect { exported ->
            when (exported.action) {
                ExportAction.SHARE -> (shareExport(context, exported) as? FileActionResult.Failed)?.let { message(it.message) }
                ExportAction.SAVE -> {
                    pendingSave = exported
                    saver.launch(CreateTypedDocument.Input(exported.name, exported.format.mime))
                }
            }
        }
    }
    LaunchedEffect(phase) {
        val failed = phase as? ExportPhase.Failed ?: return@LaunchedEffect
        message(failed.error.message)
        vm.dismissError()
    }

    val busy = phase is ExportPhase.Exporting
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            enabled = hasScore && online && !busy,
            modifier = Modifier.semantics { if (!online) contentDescription = "Export: $NEEDS_CONNECTION" },
        ) { Text(if (busy) "Exporting…" else "Export") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScoreExportFormat.entries.forEachIndexed { i, format ->
                if (i > 0) HorizontalDivider()
                DropdownMenuItem(text = { Text("Share ${format.label}") }, onClick = {
                    open = false
                    vm.export(format, ExportAction.SHARE)
                })
                DropdownMenuItem(text = { Text("Save ${format.label} file…") }, onClick = {
                    open = false
                    vm.export(format, ExportAction.SAVE)
                })
            }
        }
    }
}

/** Saves a pending [ExportedFile] as path, name, format and action names. */
private val ExportedFileSaver: Saver<ExportedFile?, List<String>> = Saver(
    save = { it?.let { f -> listOf(f.file.path, f.name, f.format.name, f.action.name) } },
    restore = { saved ->
        val format = ScoreExportFormat.entries.firstOrNull { it.name == saved.getOrNull(2) }
        val action = ExportAction.entries.firstOrNull { it.name == saved.getOrNull(3) }
        if (saved.size == 4 && format != null && action != null) ExportedFile(File(saved[0]), saved[1], format, action) else null
    },
)
