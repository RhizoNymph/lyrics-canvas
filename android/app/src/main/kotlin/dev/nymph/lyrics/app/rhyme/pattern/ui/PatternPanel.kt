package dev.nymph.lyrics.app.rhyme.pattern.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.rhyme.pattern.Detection
import dev.nymph.lyrics.app.rhyme.pattern.PatternSession
import dev.nymph.lyrics.app.rhyme.pattern.PatternTab
import dev.nymph.lyrics.app.rhyme.pattern.PatternUiState
import dev.nymph.lyrics.app.rhyme.pattern.PatternViewModel
import dev.nymph.lyrics.app.rhyme.pattern.failureText
import dev.nymph.lyrics.app.rhyme.pattern.lineWords
import dev.nymph.lyrics.app.rhyme.pattern.skipText
import dev.nymph.lyrics.app.rhyme.pattern.slotChips
import dev.nymph.lyrics.app.tags.TagsUiState
import dev.nymph.lyrics.app.ui.theme.Palette

/** The top part (lines, legend, controls) scrolls on its own so the lists keep most of the height. */
private val TOP_MAX_HEIGHT = 320.dp

/**
 * The rhyme sheet in pattern mode: header with "Back to single", the pattern lines and slot legend,
 * the tuple tray, and the Slots / Whole lines tabs. Needs the server; offline every call says
 * "needs connection".
 */
@Composable
fun PatternPanel(
    state: PatternUiState,
    session: PatternSession,
    vm: PatternViewModel,
    tags: TagsUiState<*>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToBank: ((String) -> Unit)? = null,
) {
    val board = session.board
    val chips = board?.let { slotChips(it, session.slotInfo) }.orEmpty()
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.background(Palette.Sidebar)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pattern", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Lines ${session.target.range.start + 1}–${session.target.range.end} · ${session.label}",
                    color = Palette.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = vm::exit) { Text("Back to single", fontSize = 12.sp) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close rhymes") }
        }
        if (session.ignoredSnippets > 0) {
            val others = if (session.ignoredSnippets == 1) "1 other selected snippet is" else "${session.ignoredSnippets} other selected snippets are"
            Text("Using the snippet whose lines you picked; $others ignored.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        HorizontalDivider(color = Palette.Border)
        Column(
            Modifier.heightIn(max = TOP_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val detection = session.detection) {
                Detection.Running -> {
                    Text("Finding where these lines rhyme…", color = Palette.Muted, fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Detection.Failed -> {
                    session.failure?.let { Text(failureText(it), color = Palette.Error, fontSize = 12.sp) }
                    OutlinedButton(onClick = vm::reset) { Text("Try again", fontSize = 12.sp) }
                }
                is Detection.Ready -> {
                    PatternLinesBlock(lineWords(detection.lines, detection.board, chips, session.picker), detection.board, session.pickerActions, vm)
                    if (chips.isEmpty()) {
                        Text("These lines don't share a rhyme at this threshold.", fontSize = 13.sp)
                        DetectThreshold(state, vm)
                        Text("Or tap words to make a slot.", color = Palette.Muted, fontSize = 12.sp)
                    } else {
                        SlotLegend(chips, vm)
                    }
                    session.notice?.let { Text(it, color = Palette.Error, fontSize = 12.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::reset) { Text("Reset", fontSize = 12.sp) }
                        TextButton(onClick = { controlsOpen = !controlsOpen }) { Text(if (controlsOpen) "Controls ▾" else "Controls ▸", fontSize = 12.sp) }
                    }
                    if (controlsOpen) PatternControls(state, vm, tags)
                }
            }
        }
        if (session.detection is Detection.Ready) {
            HorizontalDivider(color = Palette.Border)
            if (session.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            session.failure?.let { Text(failureText(it), color = Palette.Error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            when {
                state.tagFilterBlocked -> Text("Fix the tag filter to search", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                else -> state.skip?.let { Text(skipText(it), color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            }
            TupleTrayBar(session, vm, onAddToBank)
            TabRow(selectedTabIndex = session.tab.ordinal, containerColor = Palette.Sidebar) {
                Tab(selected = session.tab == PatternTab.SLOTS, onClick = { vm.setTab(PatternTab.SLOTS) }, text = { Text("Slots") })
                Tab(
                    selected = session.tab == PatternTab.LINES,
                    onClick = { vm.setTab(PatternTab.LINES) },
                    text = { Text("Whole lines (${session.lineMatches.total})") },
                )
            }
            val content = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)
            when (session.tab) {
                PatternTab.SLOTS -> SlotColumnsView(session, chips, vm, onAddToBank, content.padding(horizontal = 6.dp))
                PatternTab.LINES -> LineMatchesView(session, chips, vm, content)
            }
        }
    }
}
