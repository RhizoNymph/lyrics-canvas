package dev.nymph.lyrics.app.rhyme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.RhymeUiState
import dev.nymph.lyrics.app.rhyme.RhymeViewModel
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.rhyme.RhymeError
import dev.nymph.lyrics.logic.rhyme.RhymeOperation

/** The status line text: searching, empty, or "Showing a–b of total". */
fun statusText(state: RhymeUiState): String = when {
    state.loading -> "Searching…"
    state.rows.isEmpty() -> "No candidates"
    else -> "Showing ${state.shown.start}–${state.shown.end} of ${state.total}"
}

fun errorText(error: RhymeError): String =
    "${if (error.operation == RhymeOperation.MATERIALIZE) "Materialize failed" else "Query failed"} (${error.kind.name.lowercase()}): ${error.message}"

/**
 * The rhyme sheet's content (RhymeSidebar.tsx): header, query controls, status line with More,
 * error line, and the result rows. Hosted in a bottom sheet or a side pane by the canvas screen.
 */
@Composable
fun RhymePanel(state: RhymeUiState, vm: RhymeViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.background(Palette.Sidebar)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rhymes", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close rhymes") }
            }
            HorizontalDivider(color = Palette.Border)
        }
        item(key = "controls") { Box(Modifier.padding(12.dp)) { QueryControls(state, vm) } }
        item(key = "status") {
            HorizontalDivider(color = Palette.Border)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(statusText(state), color = Palette.Muted, fontSize = 12.sp)
                OutlinedButton(onClick = vm::loadMore, enabled = state.canLoadMore) { Text("More", fontSize = 12.sp) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider(color = Palette.Border)
            state.error?.let { Text(errorText(it), color = Palette.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
        items(state.rows, key = { it.id }) { candidate ->
            val dismissState = rememberSwipeToDismissBoxState()
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(Modifier.fillMaxSize().background(Palette.Border).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Dismiss", color = Palette.Muted, fontSize = 12.sp)
                    }
                },
                onDismiss = { vm.dismiss(candidate.id) },
            ) {
                CandidateRow(
                    candidate = candidate,
                    anchor = state.anchors.firstOrNull { it.id == candidate.bestAnchorId },
                    hasSlot = state.slot != null,
                    expanded = state.expandedRow == candidate.id,
                    materializing = candidate.id in state.materializing,
                    onToggle = { vm.toggleRow(candidate.id) },
                    onChain = { vm.setTypedText(candidate.text) },
                    onMaterialize = { vm.materialize(candidate.id) },
                    onDismiss = { vm.dismiss(candidate.id) },
                    modifier = Modifier.background(Palette.Sidebar),
                )
            }
        }
    }
}
