package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.PatternUiState
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.tags.TagsUiState
import dev.nymph.lyrics.app.tags.ui.ExpressionInput
import dev.nymph.lyrics.app.tags.ui.PresetMenu
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.SourceName
import java.util.Locale

private fun twoPlaces(value: Double): String = String.format(Locale.US, "%.2f", value)

/** The detection threshold (detect `min_score`, 0.30..1.00); re-detects after the debounce. */
@Composable
fun DetectThreshold(state: PatternUiState, vm: PatternViewModel) {
    Column {
        Text("Detection threshold ${twoPlaces(state.params.detectMinScore)}", color = Palette.Muted, fontSize = 11.sp)
        Slider(
            value = state.params.detectMinScore.toFloat(),
            onValueChange = { vm.setDetectMinScore(Math.round(it * 20) / 20.0) },
            valueRange = PatternLimits.DETECT_MIN_SCORE_FLOOR.toFloat()..1f,
            steps = 13,
        )
    }
}

/** Min score, sources, rare words, "Only my lines matching" and the detection threshold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternControls(state: PatternUiState, vm: PatternViewModel, tags: TagsUiState<*>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text("Min score ${twoPlaces(state.params.minScore)}", color = Palette.Muted, fontSize = 11.sp)
            Slider(
                value = state.params.minScore.toFloat(),
                onValueChange = { vm.setMinScore(Math.round(it * 20) / 20.0) },
                valueRange = 0f..1f,
                steps = 19,
            )
        }
        FlowRow(verticalArrangement = Arrangement.Center) {
            Toggle("notes", state.params.sources.notes) { vm.toggleSource(SourceName.NOTES) }
            Toggle("dictionary", state.params.sources.dictionary) { vm.toggleSource(SourceName.DICTIONARY) }
            Toggle("rare words", state.params.includeRare) { vm.setIncludeRare(!state.params.includeRare) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Only my lines matching", color = Palette.Muted, fontSize = 11.sp)
            ExpressionInput(
                field = state.tagFilter,
                tags = tags.tags,
                label = "Tag filter for notes",
                onEdit = vm::editTagFilter,
                onAccept = vm::acceptTagFilterSuggestion,
                onInsertTag = vm::insertTagFilterTag,
                note = when {
                    !state.params.sources.notes -> "notes is off, so this is not used"
                    state.tagFilterBlocked -> "no search until it is fixed"
                    else -> null
                },
                trailingIcon = if (state.tagFilter.text.isNotEmpty()) {
                    { IconButton(onClick = vm::clearTagFilter) { Icon(Icons.Default.Close, contentDescription = "Clear tag filter") } }
                } else {
                    null
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val canvasFilter = tags.filterBar.appliedText.trim()
                TextButton(onClick = { vm.setTagFilterText(canvasFilter) }, enabled = canvasFilter.isNotEmpty()) { Text("Use canvas filter", fontSize = 13.sp) }
                PresetMenu(tags.presets, onApply = { vm.setTagFilterText(it.expression) })
            }
        }
        DetectThreshold(state, vm)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, fontSize = 13.sp)
    }
}
