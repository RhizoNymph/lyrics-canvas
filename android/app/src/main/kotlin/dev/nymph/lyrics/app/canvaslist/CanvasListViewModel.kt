package dev.nymph.lyrics.app.canvaslist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.CanvasRepository
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

class CanvasListViewModel(private val canvases: CanvasRepository) : ViewModel() {
    private val _state = MutableStateFlow<CanvasListUiState>(CanvasListUiState.Loading)
    val state: StateFlow<CanvasListUiState> = _state.asStateFlow()

    private val _events = Channel<CanvasListEvent>(Channel.BUFFERED)
    val events: Flow<CanvasListEvent> = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = when (val result = canvases.list()) {
                is ApiResult.Success -> CanvasListUiState.Ready(result.value)
                is ApiResult.Failure -> CanvasListUiState.Failed(result.error.userMessage())
            }
        }
    }

    /** Create a canvas (blank title = the server default) and open it. */
    fun create(title: String) {
        val name = title.trim().ifEmpty { CanvasCreateRequest().title }
        viewModelScope.launch {
            when (val result = canvases.create(name)) {
                is ApiResult.Success -> {
                    _state.update { s -> if (s is CanvasListUiState.Ready) s.copy(canvases = listOf(result.value) + s.canvases) else s }
                    _events.trySend(CanvasListEvent.Open(result.value.id))
                }
                is ApiResult.Failure -> _events.trySend(CanvasListEvent.Message(result.error.userMessage()))
            }
        }
    }
}
