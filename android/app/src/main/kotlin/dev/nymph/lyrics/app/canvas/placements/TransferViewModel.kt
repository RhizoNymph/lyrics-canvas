package dev.nymph.lyrics.app.canvas.placements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.placements.CanvasTitleCheck
import dev.nymph.lyrics.logic.placements.CanvasTitleProblem
import dev.nymph.lyrics.logic.placements.checkCanvasTitle
import dev.nymph.lyrics.logic.placements.toPlacementError
import dev.nymph.lyrics.logic.placements.transferMessage
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasCreateRequest
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferMode
import dev.nymph.lyrics.model.TransferRequest
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Move to canvas…" / "Also show on canvas…": the picker of the user's other canvases (searchable,
 * newest updated first, "New canvas…" creating one and then acting on it) and the transfer call.
 * Lives in the canvas back-stack entry next to [port]. Never throws: failures stay in the open
 * sheet ([TransferUiState.Picking.error], [NewCanvasPrompt.error]).
 */
class TransferViewModel(
    private val canvasId: CanvasId,
    private val canvases: CanvasRepository,
    private val snippets: SnippetRepository,
    private val port: PlacementCanvasPort,
    private val updates: CanvasUpdates = CanvasUpdates(),
) : ViewModel() {
    private val _state = MutableStateFlow<TransferUiState>(TransferUiState.Closed)
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _events = Channel<TransferEvent>(Channel.BUFFERED)
    val events: Flow<TransferEvent> = _events.receiveAsFlow()

    private inline fun updatePicking(transform: (TransferUiState.Picking) -> TransferUiState.Picking) {
        _state.update { if (it is TransferUiState.Picking) transform(it) else it }
    }

    private fun picking(): TransferUiState.Picking? = _state.value as? TransferUiState.Picking

    /** Open the picker for [ids] (nothing for an empty selection) and load the canvases. */
    fun open(mode: TransferMode, ids: Set<SnippetId>) {
        if (ids.isEmpty()) return
        _state.value = TransferUiState.Picking(mode, canvasId, ids)
        loadCanvases()
    }

    fun retryLoad() = loadCanvases()

    private fun loadCanvases() {
        updatePicking { it.copy(choices = CanvasChoices.Loading) }
        viewModelScope.launch {
            val choices = when (val result = canvases.list()) {
                is ApiResult.Success -> CanvasChoices.Loaded(result.value)
                is ApiResult.Failure -> CanvasChoices.Failed(result.error.userMessage())
            }
            updatePicking { it.copy(choices = choices) }
        }
    }

    fun setQuery(query: String) = updatePicking { it.copy(query = query) }

    fun close() {
        _state.value = TransferUiState.Closed
    }

    /** Transfer the picked snippets to [target]. */
    fun choose(target: Canvas) {
        val open = picking() ?: return
        if (open.busy) return
        transferTo(open, target)
    }

    private fun transferTo(open: TransferUiState.Picking, target: Canvas) {
        if (target.id == open.source) {
            updatePicking { it.copy(error = "Pick a different canvas: the snippets are already here.") }
            return
        }
        val request = TransferRequest.of(open.ids, target.id, open.mode)
        if (request == null) {
            updatePicking { it.copy(error = "Select at most ${TransferRequest.MAX_SNIPPETS} snippets.") }
            return
        }
        updatePicking { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = snippets.transfer(open.source, request)) {
                is ApiResult.Success -> {
                    port.onTransferred(result.value, CanvasRef(target.id, target.title))
                    _state.value = TransferUiState.Closed
                    _events.trySend(TransferEvent.Done(transferMessage(open.mode, result.value, target.title), target.id))
                }
                is ApiResult.Failure -> updatePicking { it.copy(busy = false, error = toPlacementError(result.error).userMessage()) }
            }
        }
    }

    /** "New canvas…": ask for a title. */
    fun startNewCanvas() = updatePicking { if (it.busy) it else it.copy(newCanvas = NewCanvasPrompt()) }

    fun dismissNewCanvas() = updatePicking { it.copy(newCanvas = null) }

    /**
     * Create the canvas ([text] trimmed; blank takes the server default "Untitled Canvas", as the
     * list's create) and transfer to it. A refused title stays in the prompt.
     */
    fun submitNewCanvas(text: String) {
        val open = picking() ?: return
        if (open.busy || open.newCanvas == null) return
        val title = when (val check = checkCanvasTitle(text)) {
            is CanvasTitleCheck.Valid -> check.title
            is CanvasTitleCheck.Invalid -> when (check.problem) {
                CanvasTitleProblem.EMPTY -> CanvasCreateRequest().title
                CanvasTitleProblem.TOO_LONG -> {
                    updatePicking { it.copy(newCanvas = NewCanvasPrompt(check.problem.userMessage())) }
                    return
                }
            }
        }
        updatePicking { it.copy(busy = true, newCanvas = NewCanvasPrompt()) }
        viewModelScope.launch {
            when (val result = canvases.create(title)) {
                is ApiResult.Success -> {
                    val created = result.value
                    updates.publish(created)
                    updatePicking { p ->
                        val choices = (p.choices as? CanvasChoices.Loaded)?.let { CanvasChoices.Loaded(listOf(created) + it.all) } ?: p.choices
                        p.copy(newCanvas = null, choices = choices)
                    }
                    picking()?.let { transferTo(it.copy(busy = false), created) }
                }
                is ApiResult.Failure -> updatePicking {
                    it.copy(busy = false, newCanvas = NewCanvasPrompt(toPlacementError(result.error).userMessage()))
                }
            }
        }
    }
}
