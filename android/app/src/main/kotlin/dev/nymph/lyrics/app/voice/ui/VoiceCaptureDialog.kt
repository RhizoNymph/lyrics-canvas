package dev.nymph.lyrics.app.voice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.app.voice.recordings.formatDuration
import dev.nymph.lyrics.app.voice.retryLabel
import dev.nymph.lyrics.app.voice.userMessage
import dev.nymph.lyrics.logic.voice.PermissionStatus
import dev.nymph.lyrics.logic.voice.RecorderState
import dev.nymph.lyrics.logic.voice.SilenceTimeout
import dev.nymph.lyrics.model.voice.VoiceTakeTarget

/** Everything the recording modal can ask for. */
data class VoiceCaptureActions(
    val onTap: () -> Unit,
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onDiscard: () -> Unit,
    val onSilence: (SilenceTimeout) -> Unit,
    val onVocabularyHints: (Boolean) -> Unit,
    val onAskAgain: () -> Unit,
    val onOpenSettings: () -> Unit,
)

private fun SilenceTimeout.label(): String = if (this == SilenceTimeout.OFF) "Off" else "$seconds s"

private fun VoiceTakeTarget.label(): String = when (this) {
    is VoiceTakeTarget.New -> "New snippet"
    is VoiceTakeTarget.Append -> "Adding to this snippet"
}

/**
 * The full-screen recording modal. While recording, a pointer down anywhere outside the buttons
 * and chips is a tap (next line; two within 300 ms stop). Back cancels, except while recording,
 * where only Stop / Cancel end the take (a stray back gesture must not lose it).
 */
@Composable
fun VoiceCaptureDialog(state: RecorderState.Active, silence: SilenceTimeout, vocabularyHints: Boolean, actions: VoiceCaptureActions) {
    val recording = state is RecorderState.Recording
    Dialog(
        onDismissRequest = { if (!recording) actions.onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        BackHandler(enabled = !recording) { actions.onCancel() }
        Surface(Modifier.fillMaxSize(), color = Palette.CanvasBackground) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (state) {
                    is RecorderState.Permission -> PermissionPane(state, actions)
                    is RecorderState.Starting -> Busy("Starting the microphone…", actions.onCancel)
                    is RecorderState.Recording -> RecordingPane(state, vocabularyHints, actions)
                    is RecorderState.Finishing -> Busy("Finishing the recording…", actions.onCancel)
                    is RecorderState.Uploading -> Busy("Transcribing…", actions.onCancel)
                    is RecorderState.Done -> Busy("Saved", null)
                    is RecorderState.Held -> Message(
                        text = state.reason.userMessage(),
                        primary = state.reason.retryLabel() to actions.onRetry,
                        secondary = "Discard" to actions.onDiscard,
                    )
                    is RecorderState.RecorderFailed -> Message(
                        text = state.failure.userMessage(),
                        primary = "Try again" to actions.onRetry,
                        secondary = "Close" to actions.onDiscard,
                    )
                }
                if (state !is RecorderState.Recording) {
                    RecorderOptions(
                        silence = silence,
                        onSilence = actions.onSilence,
                        vocabularyHints = vocabularyHints,
                        onVocabularyHints = actions.onVocabularyHints,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    )
                }
                Text(
                    state.target.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.FallbackText,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).padding(top = 96.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingPane(state: RecorderState.Recording, vocabularyHints: Boolean, actions: VoiceCaptureActions) {
    val haptics = LocalHapticFeedback.current
    val onTap by rememberUpdatedState(actions.onTap)
    Box(Modifier.fillMaxSize()) {
        // The tap surface sits below everything else; buttons and chips on top take their own touches.
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Tap anywhere for the next line, double-tap to stop"
                    onClick(label = "Next line") {
                        onTap()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onTap()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
        )
        RecorderOptions(
            silence = state.silence,
            onSilence = actions.onSilence,
            vocabularyHints = vocabularyHints,
            onVocabularyHints = actions.onVocabularyHints,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        )
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(formatDuration(state.elapsedMs), fontSize = 56.sp, fontFamily = FontFamily.Monospace, color = Palette.CellText)
            Text("Line ${state.lineNumber}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { state.level }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(8.dp))
            Text(
                "Tap anywhere for the next line · double-tap to stop",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.FallbackText,
                textAlign = TextAlign.Center,
            )
            if (state.silence != SilenceTimeout.OFF) {
                Text(
                    "Stops after ${state.silence.seconds} s of silence",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.FallbackText,
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = actions.onCancel) { Text("Cancel") }
            Button(onClick = actions.onStop) { Text("Stop") }
        }
    }
}

/** The auto-stop chips and, below them, the "Use word bank as hints" switch. */
@Composable
private fun RecorderOptions(
    silence: SilenceTimeout,
    onSilence: (SilenceTimeout) -> Unit,
    vocabularyHints: Boolean,
    onVocabularyHints: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        SilenceChips(silence, onSilence)
        VocabularyHintsSwitch(vocabularyHints, onVocabularyHints)
    }
}

@Composable
private fun SilenceChips(current: SilenceTimeout, onSelect: (SilenceTimeout) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Auto-stop", style = MaterialTheme.typography.labelMedium, color = Palette.FallbackText)
        SilenceTimeout.entries.forEach { option ->
            FilterChip(selected = option == current, onClick = { onSelect(option) }, label = { Text(option.label()) })
        }
    }
}

@Composable
private fun PermissionPane(state: RecorderState.Permission, actions: VoiceCaptureActions) {
    when (state.status) {
        PermissionStatus.REQUESTING -> Busy("Asking for microphone access…", actions.onCancel)
        PermissionStatus.RATIONALE -> Message(
            text = "Lyrics Canvas records your voice to turn it into lines. Allow microphone access to record.",
            primary = "Allow" to actions.onAskAgain,
            secondary = "Cancel" to actions.onCancel,
        )
        PermissionStatus.DENIED -> Message(
            text = "Recording needs microphone access.",
            primary = "Ask again" to actions.onAskAgain,
            secondary = "Cancel" to actions.onCancel,
        )
        PermissionStatus.PERMANENTLY_DENIED -> Message(
            text = "Microphone access is turned off for Lyrics Canvas. Turn it on in the app settings (Permissions > Microphone), then come back.",
            primary = "Open settings" to actions.onOpenSettings,
            secondary = "Cancel" to actions.onCancel,
        )
    }
}

@Composable
private fun Busy(text: String, onCancel: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (onCancel != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun Message(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = secondary.second) { Text(secondary.first) }
            Button(onClick = primary.second) { Text(primary.first) }
        }
    }
}
