package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.LineWord
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.SlotChip
import dev.nymph.lyrics.app.rhyme.pattern.refusalText
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.WordAction

/** The slot colour of a chip, dimmed when the slot is partial or off. */
fun slotColor(chip: SlotChip): Color = Palette.slot(chip.index).copy(alpha = if (chip.dimmed) Palette.DIMMED_SLOT_ALPHA else 1f)

/**
 * The pattern lines, each word tappable (members highlighted in their slot's colour), with the
 * tapped word's picker under its line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternLinesBlock(words: List<List<LineWord>>, board: SlotBoard, actions: List<WordAction>, vm: PatternViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        words.forEach { line ->
            val lineIndex = line.firstOrNull()?.lineIndex ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${lineIndex + 1}", color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(22.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    line.forEach { word -> Word(word, vm) }
                }
            }
            if (line.any { it.selected }) WordPicker(actions, board, vm)
        }
    }
}

@Composable
private fun Word(word: LineWord, vm: PatternViewModel) {
    val colour = word.slot?.let { slotColor(it) }
    Box(
        Modifier
            .background(colour ?: Palette.Strip, RoundedCornerShape(3.dp))
            .then(if (word.selected) Modifier.border(BorderStroke(2.dp, Palette.SelectionBorder), RoundedCornerShape(3.dp)) else Modifier)
            .clickable { vm.tapWord(word.lineIndex, word.word) }
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Text(
            word.text,
            color = if (colour != null) Palette.RangeHighlightText else Palette.CellText,
            fontSize = 13.sp,
            fontWeight = if (word.slot != null) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** "Add to A/B/...", "New slot", "Remove from X"; a refused entry is disabled and says why. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordPicker(actions: List<WordAction>, board: SlotBoard, vm: PatternViewModel) {
    Column(Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { action ->
                val label = when (action) {
                    is WordAction.AddTo -> "Add to ${action.letter}"
                    is WordAction.NewSlot -> "New slot"
                    is WordAction.Remove -> "Remove from ${action.letter}"
                }
                OutlinedButton(onClick = { vm.edit(action.edit) }, enabled = action.refusal == null) { Text(label, fontSize = 12.sp) }
            }
            OutlinedButton(onClick = vm::closePicker) { Text("Cancel", fontSize = 12.sp) }
        }
        actions.mapNotNull { it.refusal }.distinct().forEach { Text(refusalText(it, board), color = Palette.Muted, fontSize = 11.sp) }
    }
}

/** The legend: one chip per slot (`A  EH D IY  3/3`); tap toggles it on / off, × drops it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlotLegend(chips: List<SlotChip>, vm: PatternViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { chip ->
            Row(
                Modifier
                    .alpha(if (chip.on) 1f else 0.5f)
                    .border(BorderStroke(1.dp, slotColor(chip)), RoundedCornerShape(12.dp))
                    .clickable { vm.toggleSlot(chip.id) }
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(10.dp).background(slotColor(chip), CircleShape))
                Text(chip.letter, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(chip.key ?: "…", color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(chip.coverageText, color = if (chip.full) Palette.Fits else Palette.Muted, fontSize = 11.sp)
                Text(
                    "×",
                    color = Palette.CellText,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { vm.dropSlot(chip.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
