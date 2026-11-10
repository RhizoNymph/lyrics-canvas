package dev.nymph.lyrics.app.canvaslist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.canvas.placements.userMessage
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.app.canvas.rename.CanvasRenamer
import dev.nymph.lyrics.app.canvas.rename.RenameDialog
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.logic.placements.CanvasTitleCheck
import dev.nymph.lyrics.logic.placements.CanvasTitleProblem
import dev.nymph.lyrics.logic.placements.checkCanvasTitle
import dev.nymph.lyrics.logic.placements.toPlacementError
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasCreateRequest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CanvasListUiState {
    data object Loading : CanvasListUiState

    data class Failed(val message: String) : CanvasListUiState

    data class Ready(val canvases: List<Canvas>) : CanvasListUiState
}

sealed interface CanvasListEvent {
    data class Open(val id: CanvasId) : CanvasListEvent

    data class Message(val text: String) : CanvasListEvent
}

/**
 * The canvas list: load, create (and open), rename ([rename] dialog). Canvases renamed or created
 * elsewhere arrive on [updates] and replace (or are prepended to) the loaded list.
 */
class CanvasListViewModel(
    private val canvases: CanvasRepository,
    private val updates: CanvasUpdates = CanvasUpdates(),
) : ViewModel() {
    private val _state = MutableStateFlow<CanvasListUiState>(CanvasListUiState.Loading)
    val state: StateFlow<CanvasListUiState> = _state.asStateFlow()

    private val _events = Channel<CanvasListEvent>(Channel.BUFFERED)
    val events: Flow<CanvasListEvent> = _events.receiveAsFlow()

    private val renamer = CanvasRenamer(canvases, viewModelScope, updates, ::upsert)

    val rename: StateFlow<RenameDialog> = renamer.state

    init {
        refresh()
        viewModelScope.launch { updates.updates.collect(::upsert) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = when (val result = canvases.list()) {
                is ApiResult.Success -> CanvasListUiState.Ready(result.value)
                is ApiResult.Failure -> CanvasListUiState.Failed(result.error.userMessage())
            }
        }
    }

    /** Replace the canvas with [canvas]'s id in place, or put a new one first. */
    private fun upsert(canvas: Canvas) = _state.update { s ->
        if (s !is CanvasListUiState.Ready) return@update s
        if (s.canvases.any { it.id == canvas.id }) {
            s.copy(canvases = s.canvases.map { if (it.id == canvas.id) canvas else it })
        } else {
            s.copy(canvases = listOf(canvas) + s.canvases)
        }
    }

    /** Create a canvas (trimmed; blank = the server default title) and open it. */
    fun create(title: String) {
        val name = when (val check = checkCanvasTitle(title)) {
            is CanvasTitleCheck.Valid -> check.title
            is CanvasTitleCheck.Invalid -> when (check.problem) {
                CanvasTitleProblem.EMPTY -> CanvasCreateRequest().title
                CanvasTitleProblem.TOO_LONG -> {
                    _events.trySend(CanvasListEvent.Message(check.problem.userMessage()))
                    return
                }
            }
        }
        viewModelScope.launch {
            when (val result = canvases.create(name)) {
                is ApiResult.Success -> {
                    upsert(result.value)
                    _events.trySend(CanvasListEvent.Open(result.value.id))
                }
                is ApiResult.Failure -> _events.trySend(CanvasListEvent.Message(toPlacementError(result.error).userMessage()))
            }
        }
    }

    /** Long-press or the row's ⋮ → Rename: open the dialog for [canvas]. */
    fun startRename(canvas: Canvas) = renamer.open(canvas.id, canvas.title)

    fun submitRename(text: String) = renamer.submit(text)

    fun dismissRename() = renamer.dismiss()
}
