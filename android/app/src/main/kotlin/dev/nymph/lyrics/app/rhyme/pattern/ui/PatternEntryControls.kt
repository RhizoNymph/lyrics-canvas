package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.PatternSeed
import dev.nymph.lyrics.app.rhyme.pattern.PatternUiState
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.rangeProblemText
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.pattern.LinePick
import dev.nymph.lyrics.logic.rhyme.pattern.activeFor
import dev.nymph.lyrics.logic.rhyme.pattern.defaultPatternLineCount
import dev.nymph.lyrics.logic.rhyme.pattern.isFirst
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.SnippetId

/** What the single-mode chips need to offer pattern mode. */
class PatternEntry(val state: PatternUiState, val vm: PatternViewModel, val seed: PatternSeed) {
    fun picking(id: SnippetId): Boolean = state.linePick.activeFor(id)

    fun hint(id: SnippetId): String? {
        if (!picking(id)) return null
        state.linePickProblem?.let { return rangeProblemText(it) }
        return if (state.linePick is LinePick.AwaitingLast) "Now tap the last line (or the first again to clear)." else "Tap the first line of the pattern."
    }
}

/** The chip expansion's "Lines" toggle: tap line numbers to pick a range of 2+ lines for pattern mode. */
@Composable
fun LinesToggle(entry: PatternEntry, id: SnippetId) {
    val on = entry.picking(id)
    Text(
        if (on) "Lines: on" else "Lines",
        color = if (on) Palette.RangeHighlight else Palette.CellText,
        fontSize = 11.sp,
        modifier = Modifier
            .border(BorderStroke(1.dp, if (on) Palette.RangeHighlight else Palette.InputBorder), RoundedCornerShape(10.dp))
            .clickable { entry.vm.toggleLinePick(id) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A chip line's number; tappable while that chip picks lines (the picked first line is highlighted). */
@Composable
fun LineNumber(entry: PatternEntry?, id: SnippetId, index: Int) {
    val picking = entry?.picking(id) == true
    val first = entry?.state?.linePick?.isFirst(id, index) == true
    Text(
        "${index + 1}",
        color = if (first) Palette.RangeHighlightText else if (picking) Palette.RangeHighlight else Palette.Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .width(if (picking) 32.dp else 20.dp)
            .background(if (first) Palette.RangeHighlight else Color.Transparent, RoundedCornerShape(3.dp))
            .then(if (picking && entry != null) Modifier.clickable { entry.vm.onLineTap(id, index, entry.seed) } else Modifier)
            .padding(vertical = if (picking) 8.dp else 0.dp, horizontal = 2.dp),
    )
}

/** "Pattern (last N lines)" with a 2..16 stepper, for the one selected snippet. */
@Composable
fun LastLinesShortcut(entry: PatternEntry, id: SnippetId, lineCount: Int) {
    if (lineCount < PatternLimits.MIN_LINES) return
    val n = entry.state.lastLines ?: defaultPatternLineCount(lineCount)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(onClick = { entry.vm.enterLastLines(id, entry.seed) }) { Text("Pattern (last $n lines)", fontSize = 12.sp) }
        TextButton(onClick = { entry.vm.setLastLines(n - 1) }, enabled = n > PatternLimits.MIN_LINES) { Text("−") }
        TextButton(onClick = { entry.vm.setLastLines(n + 1) }, enabled = n < PatternLimits.MAX_LINES) { Text("+") }
    }
    val problem = entry.state.linePickProblem
    if (problem != null && entry.state.linePick == LinePick.Off) Text(rangeProblemText(problem), color = Palette.Error, fontSize = 11.sp)
}
