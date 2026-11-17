package dev.nymph.lyrics.app.score.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.sync.LocalSyncUi
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.score.tools.DRAFT_DENOMINATORS
import dev.nymph.lyrics.logic.score.tools.DRAFT_METER_PRESETS
import dev.nymph.lyrics.logic.score.tools.DraftSource
import dev.nymph.lyrics.logic.score.tools.takeLabel
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap

/** Shown instead of the form while the server is unreachable. */
const val DRAFT_OFFLINE_MESSAGE: String =
    "Drafts from the server need a connection. Offline, use \"From grid\" in the editor's Make score, which drafts on this device."

/** The note-length choices of a grid draft, with labels. */
val ScoreSnap.label: String
    get() = when (this) {
        ScoreSnap.EIGHTH -> "1/8"
        ScoreSnap.SIXTEENTH -> "1/16"
        ScoreSnap.THIRTY_SECOND -> "1/32"
        ScoreSnap.EIGHTH_TRIPLET -> "1/8 triplet"
        ScoreSnap.SIXTEENTH_TRIPLET -> "1/16 triplet"
    }

/**
 * The "Make score" draft sheet for [snippetId] (contract name `ScoreDraftSheet(onDraft)`): the
 * editor opens it and saves the score [onDraft] hands back (then the sheet is gone: the host
 * stops showing it). Online only; offline it says so. One ViewModel per [key].
 */
@Composable
fun ScoreDraftSheet(
    container: AppContainer,
    snippetId: SnippetId,
    onDraft: (Score) -> Unit,
    onDismiss: () -> Unit,
    key: String = "score-draft-${snippetId.value}",
) {
    val vm = viewModel(key = key) { ScoreDraftViewModel(snippetId, container.scoreTools, container.voice) }
    ScoreDraftSheet(vm, onDraft, onDismiss)
}

/** [ScoreDraftSheet] for a given ViewModel (tests, previews, a host that owns the ViewModel). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreDraftSheet(vm: ScoreDraftViewModel, onDraft: (Score) -> Unit, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val online = LocalSyncUi.current.online
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ScoreDraftContent(
            state = state,
            online = online,
            actions = DraftActions(
                setSource = vm::setSource,
                setNote = vm::setNote,
                setMeterNum = vm::setMeterNum,
                setMeterDen = vm::setMeterDen,
                setMeter = vm::setMeter,
                setBpm = vm::setBpm,
                pickRecording = vm::pickRecording,
                retryTakes = vm::retryTakes,
                draft = vm::draft,
                accept = { vm.accept()?.let(onDraft) },
                cancel = onDismiss,
            ),
        )
    }
}

/** Everything the sheet's content can ask for. */
data class DraftActions(
    val setSource: (DraftSource) -> Unit,
    val setNote: (ScoreSnap) -> Unit,
    val setMeterNum: (String) -> Unit,
    val setMeterDen: (Int) -> Unit,
    val setMeter: (dev.nymph.lyrics.model.score.DraftMeter) -> Unit,
    val setBpm: (String) -> Unit,
    val pickRecording: (dev.nymph.lyrics.model.voice.RecordingId) -> Unit,
    val retryTakes: () -> Unit,
    val draft: () -> Unit,
    val accept: () -> Unit,
    val cancel: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreDraftContent(state: ScoreDraftUiState, online: Boolean, actions: DraftActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Make score", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (!online) {
            Text(DRAFT_OFFLINE_MESSAGE, color = Palette.Muted, fontSize = 13.sp)
            TextButton(onClick = actions.cancel) { Text("Close") }
            return@Column
        }
        val form = state.form
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DraftSource.entries.forEach { source ->
                FilterChip(selected = form.source == source, onClick = { actions.setSource(source) }, label = { Text(source.label) })
            }
        }

        if (form.source == DraftSource.RECORDING) TakePicker(state, actions)

        if (form.source == DraftSource.GRID) {
            Column {
                Caption("Note length (one per syllable; a rest takes one beat)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoreSnap.entries.forEach { snap ->
                        FilterChip(selected = form.note == snap, onClick = { actions.setNote(snap) }, label = { Text(snap.label) })
                    }
                }
            }
        }

        Column {
            Caption("Meter")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DRAFT_METER_PRESETS.forEach { m ->
                    val selected = form.meterNum.trim() == m.num.toString() && form.meterDen == m.den
                    FilterChip(selected = selected, onClick = { actions.setMeter(m) }, label = { Text("${m.num}/${m.den}") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.meterNum,
                    onValueChange = actions.setMeterNum,
                    label = { Text("beats") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
                Text("/", fontSize = 18.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DRAFT_DENOMINATORS.forEach { den ->
                        FilterChip(selected = form.meterDen == den, onClick = { actions.setMeterDen(den) }, label = { Text("$den") })
                    }
                }
            }
        }

        OutlinedTextField(
            value = form.bpm,
            onValueChange = actions.setBpm,
            label = { Text(if (form.source == DraftSource.RECORDING) "Tempo (bpm, blank = estimate)" else "Tempo (bpm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        state.problem?.let { Text(it.message, color = Palette.Error, fontSize = 12.sp) }

        when (val phase = state.phase) {
            DraftPhase.Drafting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is DraftPhase.Failed -> Text(phase.error.message, color = Palette.Error, fontSize = 13.sp)
            is DraftPhase.Ready -> DraftResult(phase)
            DraftPhase.Editing -> Unit
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = actions.cancel) { Text("Cancel") }
            if (state.phase is DraftPhase.Ready) {
                TextButton(onClick = actions.draft, enabled = state.canDraft) { Text("Draft again") }
                Button(onClick = actions.accept) { Text("Use this draft") }
            } else {
                Button(onClick = actions.draft, enabled = state.canDraft) {
                    Text(if (state.phase == DraftPhase.Drafting) "Drafting…" else "Make draft")
                }
            }
        }
    }
}

@Composable
private fun TakePicker(state: ScoreDraftUiState, actions: DraftActions) {
    Column {
        Caption("Recording")
        when (val takes = state.takes) {
            DraftTakes.NotLoaded, DraftTakes.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is DraftTakes.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load recordings: ${takes.message}", color = Palette.Error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.retryTakes) { Text("Retry") }
            }
            is DraftTakes.Loaded -> if (takes.items.isEmpty()) {
                Text("This snippet has no recordings yet. Record a take first.", color = Palette.Muted, fontSize = 12.sp)
            } else {
                takes.items.forEach { take ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.form.recordingId == take.id,
                            onClick = { actions.pickRecording(take.id) },
                            enabled = take.hasTimings,
                        )
                        Column {
                            Text(takeLabel(take), fontSize = 13.sp, color = if (take.hasTimings) Palette.Text else Palette.Muted)
                            Text(take.transcript.replace('\n', ' '), fontSize = 11.sp, color = Palette.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftResult(ready: DraftPhase.Ready) {
    val score = ready.draft.score
    val meter = score.meters.firstOrNull()
    val tempo = score.tempos.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Draft ready", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(ready.summary, fontSize = 12.sp)
        val notes = score.lines.sumOf { it.notes.size }
        val shape = listOfNotNull(meter?.let { "${it.num}/${it.den}" }, tempo?.let { "${it.bpm} bpm" }, "$notes notes", "${score.lines.size} lines")
        Text(shape.joinToString(" · "), color = Palette.Muted, fontSize = 12.sp)
        Text("Nothing is saved until you use it.", color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Caption(text: String) = Text(text, color = Palette.Muted, fontSize = 11.sp)
