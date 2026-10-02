package dev.nymph.lyrics.app.editor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Chunk actions shown under a row while it holds the selection. */
@Composable
internal fun RowToolbar(
    canRemoveRests: Boolean,
    extendMode: Boolean,
    onInsertRest: () -> Unit,
    onRemoveRests: () -> Unit,
    onNudge: (Int) -> Unit,
    onExtendModeChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onInsertRest) { Text("+·") }
        TextButton(onClick = onRemoveRests, enabled = canRemoveRests) { Text("−") }
        TextButton(onClick = { onNudge(-1) }) { Text("◀") }
        TextButton(onClick = { onNudge(1) }) { Text("▶") }
        FilterChip(selected = extendMode, onClick = { onExtendModeChange(!extendMode) }, label = { Text("Extend") })
        TextButton(onClick = onClear) { Text("Clear") }
    }
}
