package dev.nymph.lyrics.app.canvas.rename

import dev.nymph.lyrics.app.canvas.placements.userMessage
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.logic.placements.CanvasTitleCheck
import dev.nymph.lyrics.logic.placements.checkCanvasTitle
import dev.nymph.lyrics.logic.placements.toPlacementError
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The rename dialog: closed, or open for one canvas with its inline error and a save in flight. */
sealed interface RenameDialog {
    data object Closed : RenameDialog

    /** [initial] seeds the text field; [error] is shown under it. */
    data class Open(val canvasId: CanvasId, val initial: String, val error: String? = null, val saving: Boolean = false) : RenameDialog
}

/**
 * The rename flow shared by the canvas list and the canvas screen: local check (trim, 1–255 code
 * points), then `PUT /api/canvases/{id}`; a refusal (local or `invalid_canvas_title`) stays in the
 * open dialog. A rename is handed to [onRenamed] and published on [updates].
 */
class CanvasRenamer(
    private val canvases: CanvasRepository,
    private val scope: CoroutineScope,
    private val updates: CanvasUpdates,
    private val onRenamed: (Canvas) -> Unit,
) {
    private val _state = MutableStateFlow<RenameDialog>(RenameDialog.Closed)
    val state: StateFlow<RenameDialog> = _state.asStateFlow()

    fun open(id: CanvasId, currentTitle: String) {
        _state.value = RenameDialog.Open(id, currentTitle)
    }

    fun dismiss() {
        _state.value = RenameDialog.Closed
    }

    fun submit(text: String) {
        val open = _state.value as? RenameDialog.Open ?: return
        if (open.saving) return
        val title = when (val check = checkCanvasTitle(text)) {
            is CanvasTitleCheck.Invalid -> {
                _state.value = open.copy(error = check.problem.userMessage())
                return
            }
            is CanvasTitleCheck.Valid -> check.title
        }
        _state.value = open.copy(error = null, saving = true)
        scope.launch {
            when (val result = canvases.rename(open.canvasId, title)) {
                is ApiResult.Success -> {
                    _state.update { if (it is RenameDialog.Open && it.canvasId == open.canvasId) RenameDialog.Closed else it }
                    onRenamed(result.value)
                    updates.publish(result.value)
                }
                is ApiResult.Failure -> _state.update { current ->
                    if (current is RenameDialog.Open && current.canvasId == open.canvasId) {
                        current.copy(error = toPlacementError(result.error).userMessage(), saving = false)
                    } else {
                        current
                    }
                }
            }
        }
    }
}
