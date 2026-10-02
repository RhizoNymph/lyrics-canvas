package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.AnchorChip
import dev.nymph.lyrics.app.rhyme.RhymeViewModel
import dev.nymph.lyrics.app.ui.theme.Palette

/**
 * One chip per selected non-blank snippet (AnchorChips.tsx): ▸ expands it to the snippet's lines
 * as syllable strips (tap a start word, then an end word), × excludes it from this query, + restores.
 */
@Composable
fun AnchorChipsView(chips: List<AnchorChip>, vm: RhymeViewModel) {
    if (chips.isEmpty()) {
        Text("No snippet selected. Select one on the canvas or type a phrase.", color = Palette.Muted, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip -> Chip(chip, vm) }
    }
}

@Composable
private fun Chip(chip: AnchorChip, vm: RhymeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .alpha(if (chip.excluded) 0.5f else 1f)
                .border(BorderStroke(1.dp, if (chip.excluded) Palette.InputBorder else Palette.RangeHighlight), RoundedCornerShape(14.dp))
                .background(if (chip.excluded) Color.Transparent else Palette.Border, RoundedCornerShape(14.dp))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChipButton(if (chip.expanded) "▾" else "▸") { vm.toggleChipExpanded(chip.snippetId) }
            Text(chip.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (chip.isPart) Text("part", color = Palette.Muted, fontSize = 10.sp)
            if (chip.rhymeKey != null && !chip.excluded) Text(chip.rhymeKey, color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            ChipButton(if (chip.excluded) "+" else "×") {
                if (chip.excluded) vm.restoreAnchor(chip.snippetId) else vm.excludeAnchor(chip.snippetId)
            }
        }
        if (chip.expanded) {
            Column(
                Modifier.padding(start = 8.dp).drawBehind { drawLine(Palette.Border, Offset.Zero, Offset(0f, size.height), strokeWidth = 2.dp.toPx()) }.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (chip.lines.isEmpty()) Text("No beat grid for this snippet.", color = Palette.Muted, fontSize = 11.sp)
                chip.lines.forEachIndexed { index, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(20.dp))
                        SyllableStrip(
                            line,
                            selected = chip.range?.takeIf { it.lineIndex == index },
                            onWordTap = { word -> vm.onWordTap(chip.snippetId, index, word) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chip.hint, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f, fill = false))
                    if (chip.range != null) {
                        Text(
                            "whole line",
                            color = Palette.CellText,
                            fontSize = 11.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { vm.resetRange(chip.snippetId) }.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipButton(label: String, onClick: () -> Unit) {
    Text(label, color = Palette.CellText, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}
