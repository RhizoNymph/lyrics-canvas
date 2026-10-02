package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.RhymeUiState
import dev.nymph.lyrics.app.rhyme.RhymeViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.ManualSlotError
import dev.nymph.lyrics.logic.rhyme.SlotMode
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.SourceName
import java.util.Locale

/** Search box, anchor chips, k, min score, slot, sources, rare words (QueryControls.tsx). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueryControls(state: RhymeUiState, vm: RhymeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            OutlinedTextField(
                value = state.typedText,
                onValueChange = vm::setTypedText,
                label = { Text("Search") },
                placeholder = { Text("A word or phrase, e.g. door hinge") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val key = state.typedKey
            if (key != null && state.typedText.isNotBlank()) Muted("key: $key", mono = true)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Anchors from selection")
            AnchorChipsView(state.chips, vm)
        }

        Column {
            Label("k (rhyming syllables)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RhymeK.entries.forEach { k ->
                    FilterChip(selected = state.params.k == k, onClick = { vm.setK(k) }, label = { Text("${k.value}") })
                }
            }
        }

        Column {
            Label("Min score ${String.format(Locale.US, "%.2f", state.params.minScore)}")
            Slider(
                value = state.params.minScore.toFloat(),
                onValueChange = { vm.setMinScore((Math.round(it * 20) / 20.0)) },
                valueRange = 0f..1f,
                steps = 19,
            )
        }

        Column {
            Label("Slot")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.params.slotMode == SlotMode.FROM_SELECTION, onClick = { vm.setSlotMode(SlotMode.FROM_SELECTION) })
                Text("from selection", fontSize = 13.sp)
                RadioButton(selected = state.params.slotMode == SlotMode.MANUAL, onClick = { vm.setSlotMode(SlotMode.MANUAL) })
                Text("manual", fontSize = 13.sp)
            }
            if (state.params.slotMode == SlotMode.FROM_SELECTION) {
                val slot = state.slot
                Muted(if (slot != null) "${slot.stress} (${slot.syllableCount} syllables)" else "none", mono = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = state.manualCount,
                        onValueChange = { vm.setManualSlot(it, state.manualStress) },
                        label = { Text("count") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                    OutlinedTextField(
                        value = state.manualStress,
                        onValueChange = { vm.setManualSlot(state.manualCount, it) },
                        label = { Text("stress e.g. 1010") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                when (state.manualError) {
                    ManualSlotError.STRESS -> Text("Stress must be digits 0, 1 or 2", color = Palette.Error, fontSize = 11.sp)
                    ManualSlotError.COUNT -> Text("Count must equal the stress length", color = Palette.Error, fontSize = 11.sp)
                    null -> Unit
                }
            }
        }

        FlowRow(verticalArrangement = Arrangement.Center) {
            Toggle("notes", state.params.sources.notes) { vm.toggleSource(SourceName.NOTES) }
            Toggle("dictionary", state.params.sources.dictionary) { vm.toggleSource(SourceName.DICTIONARY) }
            Toggle("rare words", state.params.includeRare) { vm.setIncludeRare(!state.params.includeRare) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, fontSize = 13.sp)
    }
}

@Composable
internal fun Label(text: String) = Text(text, color = Palette.Muted, fontSize = 11.sp)

@Composable
internal fun Muted(text: String, mono: Boolean = false) =
    Text(text, color = Palette.Muted, fontSize = 11.sp, fontFamily = if (mono) FontFamily.Monospace else null)
