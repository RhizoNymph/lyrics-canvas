package dev.nymph.lyrics.app.canvas.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette

/**
 * The in-canvas search box (canvas and list mode, above the tag filter bar): filters live, shows
 * "N of M" while a search applies, and clears with × or Escape (a hardware keyboard).
 */
@Composable
fun CanvasSearchBar(
    text: String,
    count: SearchCount?,
    onEdit: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Palette.Sidebar).padding(start = 12.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onEdit,
            singleLine = true,
            placeholder = { Text("Search this canvas") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (text.isNotEmpty()) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (count != null) Text(count.label, color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && text.isNotEmpty()) {
                        onClear()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}
