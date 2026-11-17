package dev.nymph.lyrics.app.score.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.ScoreToolsRepository
import dev.nymph.lyrics.logic.score.tools.ScoreToolError
import dev.nymph.lyrics.logic.score.tools.exportFileName
import dev.nymph.lyrics.logic.score.tools.toScoreToolError
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.ScoreExportFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What happens with an exported file: the share sheet, or a file the user saves with the system picker. */
enum class ExportAction { SHARE, SAVE }

sealed interface ExportPhase {
    data object Idle : ExportPhase

    data class Exporting(val format: ScoreExportFormat, val action: ExportAction) : ExportPhase

    data class Failed(val error: ScoreToolError) : ExportPhase
}

/** A finished export, ready for [action]: [file] (named [name]) sits in the app cache. */
data class ExportedFile(val file: File, val name: String, val format: ScoreExportFormat, val action: ExportAction)

/**
 * The editor's score export (MIDI / MusicXML). The server writes the file from its stored score;
 * it lands in `<exportsDir>/<snippet id>/<name>` (the folder is emptied first, so only the last
 * export is kept) and is handed to the screen on [ready] for the share sheet or a save. One export
 * at a time; failures are typed ([ExportPhase.Failed]). Never throws.
 */
class ScoreExportViewModel(
    private val snippetId: SnippetId,
    private val tools: ScoreToolsRepository,
    private val exportsDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<ExportPhase>(ExportPhase.Idle)
    val state: StateFlow<ExportPhase> = _state.asStateFlow()

    private val _ready = Channel<ExportedFile>(Channel.BUFFERED)
    val ready: Flow<ExportedFile> = _ready.receiveAsFlow()

    private val fallbackStem: String get() = "snippet-${snippetId.value.take(8)}"

    fun export(format: ScoreExportFormat, action: ExportAction) {
        if (_state.value is ExportPhase.Exporting) return
        _state.value = ExportPhase.Exporting(format, action)
        viewModelScope.launch {
            val dir = File(exportsDir, snippetId.value)
            withContext(io) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
            when (val result = tools.export(snippetId, format, File(dir, "download.${format.extension}"))) {
                is ApiResult.Success -> {
                    val name = exportFileName(result.value.suggestedName, format, fallbackStem)
                    val named = File(dir, name)
                    val file = withContext(io) { if (result.value.file.renameTo(named)) named else result.value.file }
                    _state.value = ExportPhase.Idle
                    _ready.send(ExportedFile(file, name, format, action))
                }
                is ApiResult.Failure -> _state.value = ExportPhase.Failed(toScoreToolError(result.error))
            }
        }
    }

    fun dismissError() {
        if (_state.value is ExportPhase.Failed) _state.value = ExportPhase.Idle
    }
}
