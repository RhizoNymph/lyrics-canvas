package dev.nymph.lyrics.app.voice.dataset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.logic.voice.VoiceUploadError
import dev.nymph.lyrics.logic.voice.toVoiceUploadError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.RecordingStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The "Voice dataset" dialog of the canvas list. */
sealed interface DatasetStatsState {
    data object Closed : DatasetStatsState

    data object Loading : DatasetStatsState

    data class Loaded(val stats: RecordingStats) : DatasetStatsState

    data class Failed(val error: VoiceUploadError) : DatasetStatsState
}

/** Loads the dataset stats each time the dialog opens (they change with every check). No export on Android. */
class VoiceDatasetViewModel(private val voice: VoiceRepository) : ViewModel() {
    private val _state = MutableStateFlow<DatasetStatsState>(DatasetStatsState.Closed)
    val state: StateFlow<DatasetStatsState> = _state.asStateFlow()

    private var job: Job? = null

    /** Open (or retry): show Loading, then the stats or the error. */
    fun open() {
        job?.cancel()
        _state.value = DatasetStatsState.Loading
        job = viewModelScope.launch {
            _state.value = when (val result = voice.stats()) {
                is ApiResult.Success -> DatasetStatsState.Loaded(result.value)
                is ApiResult.Failure -> DatasetStatsState.Failed(toVoiceUploadError(result.error))
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = DatasetStatsState.Closed
    }
}
