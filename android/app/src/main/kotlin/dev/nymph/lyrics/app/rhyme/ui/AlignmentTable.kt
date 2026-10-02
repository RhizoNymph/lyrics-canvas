package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeUnitFields
import java.util.Locale

/** A syllable's phones ("onset nucleus coda"), or "—" when it is not aligned. */
fun syllableText(unit: RhymeUnitFields?, index: Int?): String {
    if (unit == null || index == null) return "—"
    val syl = unit.syllables.getOrNull(index) ?: return "—"
    return (syl.onset + syl.nucleus + syl.coda).joinToString(" ")
}

/** The candidate's aligned tail against its best anchor, cell by cell (AlignmentTable.tsx). */
@Composable
fun AlignmentTable(candidate: RhymeCandidate, anchor: RhymeUnitFields?) {
    // Alignment is right-to-left; show it left-to-right like the text.
    val aligned = candidate.alignment.reversed()
    if (aligned.isEmpty()) {
        Text("No alignment.", color = Palette.Muted, fontSize = 11.sp)
        return
    }
    val rowHeight = 22.dp
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("cand", "anchor", "total").forEach {
                    Box(Modifier.height(rowHeight), contentAlignment = Alignment.CenterStart) { Text(it, color = Palette.Muted, fontSize = 11.sp) }
                }
            }
            aligned.forEach { a ->
                val bg = Palette.alignment(a.total)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cell(syllableText(candidate, a.candIndex), bg, rowHeight)
                    Cell(syllableText(anchor, a.anchorIndex), bg, rowHeight)
                    Cell(String.format(Locale.US, "%.2f", a.total), Color.Transparent, rowHeight, Palette.CellText)
                }
            }
        }
        Text(
            (if (anchor != null) "vs “${anchor.text}”" else "anchor not echoed") + " · stress ${candidate.stress}",
            color = Palette.Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Cell(text: String, background: Color, height: androidx.compose.ui.unit.Dp, color: Color = Color.White) {
    Box(
        Modifier.height(height).widthIn(min = 36.dp).background(background, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
