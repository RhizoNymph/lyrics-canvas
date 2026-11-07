package dev.nymph.lyrics.app.voice.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** The recordings list of the open snippet. */
sealed interface RecordingsList {
    data object Loading : RecordingsList

    /** Newest first, as the server sends them. */
    data class Loaded(val items: List<Recording>) : RecordingsList

    data class Failed(val message: String) : RecordingsList
}

/** At most one recording plays; every non-idle state names it. */
sealed interface Playback {
    data object Idle : Playback

    sealed interface Active : Playback {
        val id: RecordingId
    }

    /** Fetching the audio before playing. */
    data class Loading(override val id: RecordingId) : Active

    data class Playing(override val id: RecordingId) : Active

    data class Paused(override val id: RecordingId) : Active
}

val Playback.activeId: RecordingId? get() = (this as? Playback.Active)?.id

data class RecordingsUiState(
    val list: RecordingsList = RecordingsList.Loading,
    val playback: Playback = Playback.Idle,
    /** The recording awaiting delete confirmation. */
    val confirmDelete: Recording? = null,
    val deleting: Set<RecordingId> = emptySet(),
)

/**
 * The editor's recordings section: list, play / pause (audio fetched once into [cacheDir] with the
 * bearer token, then played from the file), delete with confirmation. Failures become snackbar
 * [messages]; nothing throws. Rows changed elsewhere (a checked transcript) arrive on [updates].
 */
class RecordingsViewModel(
    private val snippetId: SnippetId,
    private val voice: VoiceRepository,
    private val cacheDir: File,
    private val player: AudioPlayer,
    updates: Flow<Recording> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(RecordingsUiState())
    val state: StateFlow<RecordingsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { updates.collect(::replace) }
    }

    /** Replace the row of [recording] (same id) in place; other snippets' recordings are ignored. */
    private fun replace(recording: Recording) = _state.update { s ->
        val list = s.list as? RecordingsList.Loaded ?: return@update s
        if (list.items.none { it.id == recording.id }) return@update s
        s.copy(list = RecordingsList.Loaded(list.items.map { if (it.id == recording.id) recording else it }))
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val next = when (val result = voice.recordings(snippetId)) {
                is ApiResult.Success -> RecordingsList.Loaded(result.value)
                is ApiResult.Failure -> RecordingsList.Failed(result.error.userMessage())
            }
            _state.update { it.copy(list = next) }
        }
    }

    /** A take was just stored for this snippet: show it first without a reload. */
    fun add(recording: Recording) = _state.update { s ->
        val items = (s.list as? RecordingsList.Loaded)?.items ?: emptyList()
        s.copy(list = RecordingsList.Loaded(listOf(recording) + items.filter { it.id != recording.id }))
    }

    /** Play, pause or resume [id]; another recording playing is stopped first. */
    fun togglePlay(id: RecordingId) {
        val recording = loadedItems().firstOrNull { it.id == id } ?: return
        when (val playback = _state.value.playback) {
            is Playback.Playing -> if (playback.id == id) {
                player.pause()
                setPlayback(Playback.Paused(id))
                return
            }
            is Playback.Paused -> if (playback.id == id) {
                player.resume()
                setPlayback(Playback.Playing(id))
                return
            }
            is Playback.Loading -> if (playback.id == id) return
            Playback.Idle -> Unit
        }
        player.stop()
        setPlayback(Playback.Loading(id))
        viewModelScope.launch {
            val ready = ensureRecordingAudio(voice, cacheDir, recording)
            if (_state.value.playback != Playback.Loading(id)) return@launch
            when (ready) {
                is ApiResult.Success -> {
                    val started = player.play(ready.value) {
                        if (_state.value.playback.activeId == id) setPlayback(Playback.Idle)
                    }
                    if (started) {
                        setPlayback(Playback.Playing(id))
                    } else {
                        ready.value.delete()
                        setPlayback(Playback.Idle)
                        _messages.trySend("This recording could not be played.")
                    }
                }
                is ApiResult.Failure -> {
                    setPlayback(Playback.Idle)
                    _messages.trySend(ready.error.userMessage())
                }
            }
        }
    }

    /** Stop playing (screen left the foreground or closed). */
    fun stopPlayback() {
        player.stop()
        setPlayback(Playback.Idle)
    }

    fun requestDelete(id: RecordingId) {
        val recording = loadedItems().firstOrNull { it.id == id } ?: return
        _state.update { it.copy(confirmDelete = recording) }
    }

    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val recording = _state.value.confirmDelete ?: return
        val id = recording.id
        if (_state.value.playback.activeId == id) {
            player.stop()
            setPlayback(Playback.Idle)
        }
        _state.update { it.copy(confirmDelete = null, deleting = it.deleting + id) }
        viewModelScope.launch {
            val result = voice.deleteRecording(id)
            _state.update { s ->
                val list = s.list
                val nextList = if (result is ApiResult.Success && list is RecordingsList.Loaded) {
                    RecordingsList.Loaded(list.items.filter { it.id != id })
                } else {
                    list
                }
                s.copy(list = nextList, deleting = s.deleting - id)
            }
            when (result) {
                is ApiResult.Success -> recordingCacheFile(cacheDir, recording).delete()
                is ApiResult.Failure -> _messages.trySend(result.error.userMessage())
            }
        }
    }

    private fun loadedItems(): List<Recording> = (_state.value.list as? RecordingsList.Loaded)?.items ?: emptyList()

    private fun setPlayback(playback: Playback) = _state.update { it.copy(playback = playback) }

    override fun onCleared() {
        player.stop()
    }
}
