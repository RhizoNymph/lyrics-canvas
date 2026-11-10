package dev.nymph.lyrics.app.voice.check

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.voice.TimedLine
import dev.nymph.lyrics.logic.voice.TranscriptReference

/**
 * The read-only Whisper reference, line by line. With timings every word is tappable ([onWord]
 * seeks there) and [currentWord] is highlighted; without, the lines are plain text.
 */
@Composable
fun TranscriptReferenceView(
    reference: TranscriptReference,
    currentWord: Int?,
    onWord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Whisper heard", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        when (reference) {
            is TranscriptReference.Plain -> reference.lines.forEach { line -> PlainLine(line) }
            is TranscriptReference.Timed -> reference.lines.forEach { line -> TimedLineRow(line, currentWord, onWord) }
        }
    }
}

@Composable
private fun PlainLine(text: String) {
    Text(text.ifEmpty { " " }, style = MaterialTheme.typography.bodyMedium, color = Palette.FallbackText)
}

@Composable
private fun TimedLineRow(line: TimedLine, currentWord: Int?, onWord: (Int) -> Unit) {
    if (line.words.isEmpty()) {
        PlainLine(line.whisperText)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        line.words.forEach { (index, word) ->
            val current = index == currentWord
            Text(
                word.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) Palette.RangeHighlightText else Palette.CellText,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (current) Palette.RangeHighlight else Palette.CanvasBackground)
                    .clickable(role = Role.Button, onClickLabel = "Play from here") { onWord(index) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            )
        }
    }
}
