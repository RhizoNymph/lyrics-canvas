package dev.nymph.lyrics.app.score.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.ScoreToolsRepository
import dev.nymph.lyrics.data.repository.VoiceRepository
import dev.nymph.lyrics.logic.score.tools.DraftForm
import dev.nymph.lyrics.logic.score.tools.DraftFormCheck
import dev.nymph.lyrics.logic.score.tools.DraftFormProblem
import dev.nymph.lyrics.logic.score.tools.DraftSource
import dev.nymph.lyrics.logic.score.tools.ScoreToolError
import dev.nymph.lyrics.logic.score.tools.draftSummary
import dev.nymph.lyrics.logic.score.tools.toScoreToolError
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.score.DraftMeter
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreDraftResponse
import dev.nymph.lyrics.model.score.ScoreSnap
import dev.nymph.lyrics.model.voice.Recording
import dev.nymph.lyrics.model.voice.RecordingId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The snippet's takes for a recording draft; fetched the first time that source is chosen. */
sealed interface DraftTakes {
    data object NotLoaded : DraftTakes

    data object Loading : DraftTakes

    /** Newest first, as the server sends them (takes without word timings included, shown disabled). */
    data class Loaded(val items: List<Recording>) : DraftTakes

    data class Failed(val message: String) : DraftTakes
}

/** Where the sheet is: editing the form, waiting for the server, showing a draft, or a failure. */
sealed interface DraftPhase {
    data object Editing : DraftPhase

    data object Drafting : DraftPhase

    data class Ready(val draft: ScoreDraftResponse, val summary: String) : DraftPhase

    data class Failed(val error: ScoreToolError) : DraftPhase
}

data class ScoreDraftUiState(
    val form: DraftForm = DraftForm(),
    val takes: DraftTakes = DraftTakes.NotLoaded,
    val phase: DraftPhase = DraftPhase.Editing,
) {
    private val takeList: List<Recording> get() = (takes as? DraftTakes.Loaded)?.items.orEmpty()

    val check: DraftFormCheck get() = form.check(takeList)

    /** The form's first problem, or null when it can be sent. */
    val problem: DraftFormProblem? get() = (check as? DraftFormCheck.Invalid)?.problem

    val canDraft: Boolean get() = check is DraftFormCheck.Ready && phase != DraftPhase.Drafting
}

/**
 * The "Make score" draft sheet: from the grid (note length, meter, tempo) or from a take on the
 * snippet (meter, optional tempo: blank asks the server to estimate). The draft comes from the
 * server and is not saved; [accept] hands it to the editor, which saves it with the snippet.
 * Editing the form while a draft is shown or in flight drops it. Never throws.
 */
class ScoreDraftViewModel(
    private val snippetId: SnippetId,
    private val tools: ScoreToolsRepository,
    private val voice: VoiceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDraftUiState())
    val state: StateFlow<ScoreDraftUiState> = _state.asStateFlow()

    private var draftJob: Job? = null

    private fun edit(change: (DraftForm) -> DraftForm) {
        draftJob?.cancel()
        draftJob = null
        _state.update { it.copy(form = change(it.form), phase = DraftPhase.Editing) }
    }

    fun setSource(source: DraftSource) {
        edit { it.switchSource(source) }
        if (source == DraftSource.RECORDING && _state.value.takes is DraftTakes.NotLoaded) loadTakes()
    }

    fun setNote(note: ScoreSnap) = edit { it.copy(note = note) }

    fun setMeterNum(text: String) = edit { it.copy(meterNum = text) }

    fun setMeterDen(den: Int) = edit { it.copy(meterDen = den) }

    fun setMeter(meter: DraftMeter) = edit { it.copy(meterNum = meter.num.toString(), meterDen = meter.den) }

    fun setBpm(text: String) = edit { it.copy(bpm = text) }

    fun pickRecording(id: RecordingId) = edit { it.copy(recordingId = id) }

    fun retryTakes() = loadTakes()

    private fun loadTakes() {
        _state.update { it.copy(takes = DraftTakes.Loading) }
        viewModelScope.launch {
            when (val result = voice.recordings(snippetId)) {
                is ApiResult.Success -> _state.update { it.copy(takes = DraftTakes.Loaded(result.value), form = it.form.withRecordings(result.value)) }
                is ApiResult.Failure -> _state.update { it.copy(takes = DraftTakes.Failed(result.error.userMessage())) }
            }
        }
    }

    /** Ask the server for a draft of the current form (ignored while one is in flight or the form is invalid). */
    fun draft() {
        val s = _state.value
        if (!s.canDraft) return
        val request = (s.check as DraftFormCheck.Ready).request
        _state.update { it.copy(phase = DraftPhase.Drafting) }
        draftJob = viewModelScope.launch {
            val phase = when (val result = tools.draft(snippetId, request)) {
                is ApiResult.Success -> DraftPhase.Ready(result.value, draftSummary(result.value))
                is ApiResult.Failure -> DraftPhase.Failed(toScoreToolError(result.error))
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    /** The shown draft's score (the sheet then closes), or null when none is shown. */
    fun accept(): Score? {
        val ready = _state.value.phase as? DraftPhase.Ready ?: return null
        _state.update { it.copy(phase = DraftPhase.Editing) }
        return ready.draft.score
    }
}
