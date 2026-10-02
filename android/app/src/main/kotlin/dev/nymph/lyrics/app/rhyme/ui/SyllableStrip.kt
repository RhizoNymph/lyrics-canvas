package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.cellInRange
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridLine

/**
 * A compact one-row beat grid (SyllableStrip.tsx): stressed syllables bold, rests "·". With
 * [onWordTap], word cells are tappable (rests never are) and report their `word_index`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyllableStrip(line: GridLine, modifier: Modifier = Modifier, selected: AnchorRange? = null, onWordTap: ((Int) -> Unit)? = null) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        line.cells.forEach { cell ->
            val rest = cell.isRest
            val stressed = !rest && (cell.stress == 1 || cell.stress == 2)
            val wordIndex = cell.wordIndex
            val tappable = onWordTap != null && !rest && wordIndex != null
            val inRange = selected != null && cellInRange(cell, selected)
            val color = when {
                rest -> Palette.RestText
                inRange -> Palette.RangeHighlightText
                stressed -> Palette.StressedText
                else -> Palette.CellText
            }
            Box(
                Modifier
                    .background(if (inRange) Palette.RangeHighlight else Palette.Strip, RoundedCornerShape(3.dp))
                    .then(if (tappable) Modifier.clickable { onWordTap(wordIndex) } else Modifier)
                    // Larger targets when the cell is tappable.
                    .padding(horizontal = if (tappable) 8.dp else 5.dp, vertical = if (tappable) 6.dp else 1.dp),
            ) {
                Text(
                    if (rest) "·" else cell.syllable ?: "",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = if (stressed) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
