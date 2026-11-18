package dev.nymph.lyrics.app.editor.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.editor.score.ScoreActions
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap

/**
 * The mount point for a draft made elsewhere (from a recording, online): the editor's "Make score"
 * shows it next to the local from-grid draft. The host passes the score tools' `ScoreDraftSheet`.
 */
typealias ScoreDraftSheetSlot = @Composable (onDraft: (Score) -> Unit, onDismiss: () -> Unit) -> Unit

/** Above the editor: "Make score" without a score; Grid / Staff and Remove score (confirmed) with one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreModeBar(
    hasScore: Boolean,
    staffShown: Boolean,
    actions: ScoreActions,
    draftSheet: ScoreDraftSheetSlot?,
    modifier: Modifier = Modifier,
) {
    var making by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!hasScore) {
            TextButton(onClick = { making = true }) { Text("♪ Make score") }
        } else {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = !staffShown, onClick = actions::showGrid, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Grid") }
                SegmentedButton(selected = staffShown, onClick = actions::showStaff, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Staff") }
            }
            TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Remove score", color = Palette.Error) }
        }
    }
    if (making) {
        MakeScoreDialog(
            onDraft = { num, den, bpm, note ->
                making = false
                actions.draftFromGrid(num, den, bpm, note)
            },
            onFromRecording = draftSheet?.let { { making = false; sheet = true } },
            onDismiss = { making = false },
        )
    }
    if (sheet && draftSheet != null) {
        draftSheet({ score -> sheet = false; actions.useDraft(score) }, { sheet = false })
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove score?") },
            text = { Text("The staff (rhythm, accents, meter and tempo) is deleted when you save. The lyrics and grid stay.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; actions.removeScore() }) { Text("Remove", color = Palette.Error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** "Make score" from the grid (local, offline): meter, tempo and the note length per syllable. */
@Composable
private fun MakeScoreDialog(onDraft: (Int, Int, Double, ScoreSnap) -> Unit, onFromRecording: (() -> Unit)?, onDismiss: () -> Unit) {
    var num by remember { mutableStateOf("4") }
    var den by remember { mutableStateOf("4") }
    var bpm by remember { mutableStateOf("90") }
    var note by remember { mutableStateOf(ScoreSnap.EIGHTH) }
    var open by remember { mutableStateOf(false) }
    val input = parseBarInput(num, den, bpm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make score") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From the grid: every syllable one note, every rest one beat, each line on a new bar.", color = Palette.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(num, { num = it }, label = { Text("Beats") }, singleLine = true, modifier = Modifier.width(88.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(" / ")
                    OutlinedTextField(den, { den = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.width(88.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(bpm, { bpm = it }, label = { Text("Tempo (bpm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { open = true }) { Text("Syllable length: ${snapLabel(note)} ▾") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        ScoreSnap.entries.forEach { s -> DropdownMenuItem(text = { Text(snapLabel(s)) }, onClick = { open = false; note = s }) }
                    }
                }
                if (input == null) Text("Beats 1–32, note 1, 2, 4, 8, 16 or 32, tempo 20–400", color = Palette.Error)
                onFromRecording?.let { TextButton(onClick = it) { Text("From a recording…") } }
            }
        },
        confirmButton = {
            TextButton(enabled = input != null, onClick = { input?.let { onDraft(it.num, it.den, it.bpm, note) } }) { Text("From grid") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
