package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.placements.alsoOnText
import dev.nymph.lyrics.logic.placements.otherCanvases
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet

/**
 * "Also on: A, B" for a snippet placed on other canvases (nothing otherwise). With [onOpen] each
 * name is a button that opens that canvas.
 */
@Composable
fun AlsoOnLine(snippet: Snippet, onOpen: ((CanvasId) -> Unit)?) {
    val text = alsoOnText(snippet) ?: return
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    if (onOpen == null) {
        Text(text, color = Palette.Muted, modifier = modifier)
        return
    }
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Also on:", color = Palette.Muted)
        for (canvas in otherCanvases(snippet)) {
            TextButton(onClick = { onOpen(canvas.id) }) { Text(canvas.title) }
        }
    }
}
