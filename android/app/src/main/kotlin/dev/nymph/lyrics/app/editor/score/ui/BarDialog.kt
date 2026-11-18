package dev.nymph.lyrics.app.editor.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import dev.nymph.lyrics.logic.score.ScoreRules
import dev.nymph.lyrics.logic.score.edit.tempoValue
import dev.nymph.lyrics.logic.score.meterAtBar
import dev.nymph.lyrics.logic.score.tempoAtBar
import dev.nymph.lyrics.model.score.Score

/** What the bar dialog's fields hold: a valid meter and tempo, or null. */
data class BarInput(val num: Int, val den: Int, val bpm: Double)

/** Parse the bar dialog's fields (bar is 1-based as shown); null when any is outside format v1. */
fun parseBarInput(num: String, den: String, bpm: String): BarInput? {
    val n = num.trim().toIntOrNull() ?: return null
    val d = den.trim().toIntOrNull() ?: return null
    val b = bpm.trim().replace(',', '.').toDoubleOrNull()?.let(::tempoValue) ?: return null
    if (!ScoreRules.isValidMeter(n.toLong(), d.toLong())) return null
    return BarInput(n, d, b)
}

/**
 * Meter and tempo from bar [initialBar] (0-based; shown 1-based). Apply writes a change at that
 * bar (or bar 1's values); a change there can be removed.
 */
@Composable
fun BarDialog(score: Score, initialBar: Int, actions: ScoreActions, onDismiss: () -> Unit) {
    var barText by remember { mutableStateOf((initialBar + 1).toString()) }
    val bar = (barText.trim().toIntOrNull() ?: 1) - 1
    val meter = meterAtBar(score, bar.coerceAtLeast(0))
    val tempo = tempoAtBar(score, bar.coerceAtLeast(0))
    var num by remember(bar) { mutableStateOf(meter.num.toString()) }
    var den by remember(bar) { mutableStateOf(meter.den.toString()) }
    var bpm by remember(bar) { mutableStateOf(bpmLabel(tempo.bpm)) }
    val input = parseBarInput(num, den, bpm)
    val validBar = bar in 0..Score.MAX_BAR
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meter and tempo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(barText, { barText = it }, label = { Text("From bar") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(num, { num = it }, label = { Text("Beats") }, singleLine = true, modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(" / ")
                    OutlinedTextField(den, { den = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(bpm, { bpm = it }, label = { Text("Tempo (quarter notes per minute)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (input == null || !validBar) Text("Beats 1–32, note 1, 2, 4, 8, 16 or 32, tempo 20–400, bar 1–${Score.MAX_BAR + 1}", color = Palette.Error)
                if (bar > 0 && score.meters.any { it.bar == bar }) {
                    TextButton(onClick = { actions.removeMeter(bar); onDismiss() }) { Text("Remove the meter change at bar ${bar + 1}") }
                }
                if (bar > 0 && score.tempos.any { it.bar == bar }) {
                    TextButton(onClick = { actions.removeTempo(bar); onDismiss() }) { Text("Remove the tempo change at bar ${bar + 1}") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input != null && validBar,
                onClick = {
                    val v = input ?: return@TextButton
                    if (v.num != meter.num || v.den != meter.den) actions.setMeter(bar, v.num, v.den)
                    if (v.bpm != tempo.bpm) actions.setTempo(bar, v.bpm)
                    onDismiss()
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
