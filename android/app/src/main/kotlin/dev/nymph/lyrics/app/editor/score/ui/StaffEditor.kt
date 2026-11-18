package dev.nymph.lyrics.app.editor.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.editor.score.ScoreActions
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.logic.score.edit.noteOf
import dev.nymph.lyrics.logic.score.edit.unplacedSyllables
import dev.nymph.lyrics.logic.score.msToTick
import dev.nymph.lyrics.logic.score.wire
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import dev.nymph.lyrics.model.score.ScoreSnap

/** Snap names as the picker shows them. */
fun snapLabel(snap: ScoreSnap): String = when (snap) {
    ScoreSnap.EIGHTH -> "1/8"
    ScoreSnap.SIXTEENTH -> "1/16"
    ScoreSnap.THIRTY_SECOND -> "1/32"
    ScoreSnap.EIGHTH_TRIPLET -> "1/8 triplet"
    ScoreSnap.SIXTEENTH_TRIPLET -> "1/16 triplet"
}

/**
 * The staff view of the editor: transport and snap, the selected note's actions, the staff and
 * the tray of unplaced syllables per line (tap one, then tap the staff; or drag it onto the staff).
 */
@Composable
fun StaffEditor(score: Score, grid: GridData?, selected: SyllableRef?, actions: ScoreActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val player = remember { ScorePlayer() }
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableStateOf<Int?>(null) }
    var barDialog by remember { mutableStateOf<Int?>(null) }
    var staffCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var geometry by remember { mutableStateOf<StaffGeometry?>(null) }
    var ghost by remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(player) { onDispose { player.stop() } }
    LaunchedEffect(playing, score) {
        if (!playing) {
            playhead = null
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            val ms = player.positionMs()
            if (ms == null) {
                playing = false
                playhead = null
                break
            }
            playhead = msToTick(score, ms)
        }
    }
    val armed = selected?.takeIf { score.noteOf(it) == null }
    val note = selected?.let { score.noteOf(it) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = {
                if (playing) {
                    player.stop()
                    playing = false
                } else {
                    when (player.play(score)) {
                        null -> playing = true
                        PlaybackError.AUDIO_UNAVAILABLE -> onMessage("Playback isn't available on this device")
                    }
                }
            }) { Text(if (playing) "■ Stop" else "▶ Play") }
            SnapPicker(score.snap, actions::setSnap)
            TextButton(onClick = { barDialog = 0 }) { Text("Meter / tempo") }
            PickupStepper(score, actions)
        }
        if (note != null && selected != null) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(syllableText(grid, selected), fontSize = 13.sp, color = Palette.Text, modifier = Modifier.padding(end = 4.dp))
                TextButton(onClick = { actions.nudgeNote(-1) }) { Text("◀") }
                TextButton(onClick = { actions.nudgeNote(1) }) { Text("▶") }
                TextButton(onClick = { actions.stepLength(-1) }) { Text("Shorter") }
                TextButton(onClick = { actions.stepLength(1) }) { Text("Longer") }
                TextButton(onClick = actions::cycleAccent) { Text(accentLabel(note.a)) }
                TextButton(onClick = actions::unplaceSelected) { Text("To tray") }
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            StaffCanvas(
                score = score,
                grid = grid,
                selected = selected,
                armed = armed,
                playheadTick = playhead,
                callbacks = StaffCallbacks(
                    select = actions::selectSyllable,
                    place = actions::placeSyllable,
                    move = actions::moveNote,
                    resize = actions::resizeNote,
                    editBar = { barDialog = it },
                ),
                onCoordinates = { staffCoords = it },
                ghost = ghost,
                onGeometry = { geometry = it },
            )
            Tray(
                score = score,
                grid = grid,
                armed = armed,
                onArm = { actions.selectSyllable(if (it == armed) null else it) },
                onDrag = { root -> ghost = staffCoords?.let { c -> (root - c.positionInRoot()).takeIf { p -> c.boundsInRoot().contains(root) && p.y >= 0 } } },
                onDrop = { ref, root ->
                    ghost = null
                    val coords = staffCoords
                    val g = geometry
                    if (coords != null && g != null && coords.boundsInRoot().contains(root)) {
                        val local = root - coords.positionInRoot()
                        actions.placeSyllable(ref, g.tickAt(local.x, local.y))
                    }
                },
            )
            Text(
                if (armed != null) {
                    "Tap the staff to place “${syllableText(grid, armed)}”"
                } else {
                    "Drag a note to move it, its right end to change its length · tap a bar number for meter and tempo"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    barDialog?.let { bar -> BarDialog(score, bar, actions, onDismiss = { barDialog = null }) }
}

private fun syllableText(grid: GridData?, ref: SyllableRef): String =
    grid?.lines?.getOrNull(ref.line)?.cells?.filter { !it.isRest }?.getOrNull(ref.s)?.syllable.orEmpty()

private fun accentLabel(a: Int): String = when (a) {
    0 -> "Accent: none"
    1 -> "Accent: >"
    else -> "Accent: >>"
}

@Composable
private fun SnapPicker(current: ScoreSnap, onPick: (ScoreSnap) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Snap ${snapLabel(current)} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ScoreSnap.entries.forEach { snap ->
                DropdownMenuItem(text = { Text(snapLabel(snap)) }, onClick = { open = false; onPick(snap) })
            }
        }
    }
}

/** The pickup in snap units (− / +). */
@Composable
private fun PickupStepper(score: Score, actions: ScoreActions) {
    val step = score.snap.ticks
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pickup", fontSize = 13.sp, color = Palette.Muted)
        TextButton(onClick = { actions.setPickup((score.pickup - step).coerceAtLeast(0)) }, enabled = score.pickup > 0) { Text("−") }
        Text("${score.pickup / step}${if (score.pickup % step != 0) "+" else ""}×${score.snap.wire}", fontSize = 13.sp, color = Palette.Text)
        TextButton(onClick = { actions.setPickup(score.pickup + step) }, enabled = score.pickup + step < score.meters[0].barLength) { Text("+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tray(
    score: Score,
    grid: GridData?,
    armed: SyllableRef?,
    onArm: (SyllableRef) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: (SyllableRef, Offset) -> Unit,
) {
    val unplaced = unplacedSyllables(score, grid)
    if (unplaced.all { it.isEmpty() }) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tray", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        unplaced.forEachIndexed { line, ordinals ->
            if (ordinals.isEmpty()) return@forEachIndexed
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("L${line + 1}", fontSize = 12.sp, color = Palette.RangeHighlight, modifier = Modifier.padding(top = 4.dp))
                ordinals.forEach { s ->
                    val ref = SyllableRef(line, s)
                    TrayChip(syllableText(grid, ref), ref == armed, { onArm(ref) }, onDrag) { root -> onDrop(ref, root) }
                }
            }
        }
    }
}

@Composable
private fun TrayChip(text: String, armed: Boolean, onTap: () -> Unit, onDrag: (Offset) -> Unit, onDrop: (Offset) -> Unit) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    Text(
        text.ifEmpty { "·" },
        fontSize = 14.sp,
        color = if (armed) Palette.RangeHighlightText else Palette.Text,
        modifier = Modifier
            .onGloballyPositioned { coords = it }
            .background(if (armed) Palette.RangeHighlight else Palette.Strip, RoundedCornerShape(6.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start -> pointer = start; coords?.let { onDrag(it.positionInRoot() + start) } },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                        coords?.let { onDrag(it.positionInRoot() + pointer) }
                    },
                    onDragEnd = { coords?.let { onDrop(it.positionInRoot() + pointer) } },
                    onDragCancel = { onDrag(Offset(-1f, -1f)) },
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
