package dev.nymph.lyrics.app.editor.score.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.lyrics.app.ui.theme.Palette
import dev.nymph.lyrics.logic.score.edit.SyllableRef
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.score.Score
import kotlin.math.abs

/** Vertical layout of one staff row, as fractions of the row height. */
private object RowParts {
    const val LABEL_Y = 0.02f
    const val LINE_Y = 0.48f
    const val NOTE_HALF = 0.09f
    const val SYLLABLE_Y = 0.62f
    const val ACCENT_Y = 0.22f
}

/** Callbacks of the staff canvas (the ViewModel's [dev.nymph.lyrics.app.editor.score.ScoreActions]). */
class StaffCallbacks(
    val select: (SyllableRef?) -> Unit,
    val place: (SyllableRef, Int) -> Unit,
    val move: (SyllableRef, Int) -> Unit,
    val resize: (SyllableRef, Int) -> Unit,
    /** A tap on a bar's number: edit its meter / tempo. */
    val editBar: (Int) -> Unit,
)

/**
 * The one-line rhythm staff: bars wrapped into rows, beat ticks, each note a head with a length
 * bar, its accent (`>`) and syllable; lyric line starts marked; the playhead. Touch: tap selects a
 * note (or places the selected tray syllable on empty staff), dragging a note moves it (snapped),
 * dragging its right end changes its length; drags on empty staff scroll the page.
 */
@Composable
fun StaffCanvas(
    score: Score,
    grid: GridData?,
    selected: SyllableRef?,
    /** A tray syllable is selected: a tap on empty staff places it there. */
    armed: SyllableRef?,
    playheadTick: Int?,
    callbacks: StaffCallbacks,
    onCoordinates: (LayoutCoordinates) -> Unit,
    /** A tray syllable being dragged over the staff: its local position (for the ghost). */
    ghost: Offset?,
    onGeometry: (StaffGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val metrics = with(density) {
            StaffMetrics(
                width = maxWidth.toPx(),
                pxPerTick = 1.1.dp.toPx(),
                rowHeight = 84.dp.toPx(),
                leftPad = 12.dp.toPx(),
                rightPad = 12.dp.toPx(),
            )
        }
        val geometry = remember(score, grid, metrics) { StaffGeometry(score, grid, metrics) }
        SideEffect { onGeometry(geometry) }
        val current by rememberUpdatedState(geometry)
        val cb by rememberUpdatedState(callbacks)
        val armedNow by rememberUpdatedState(armed)
        val edgePx = with(density) { 18.dp.toPx() }
        val heightDp = with(density) { geometry.height.toDp() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .onGloballyPositioned(onCoordinates)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = current
                        val hit = g.hit(down.position.x, down.position.y, edgePx)
                        val rowTop = (down.position.y / g.metrics.rowHeight).toInt() * g.metrics.rowHeight
                        val onLabel = down.position.y - rowTop < g.metrics.rowHeight * 0.22f
                        var dragged = false
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (hit is StaffHit.Empty) {
                                if (abs(change.position.y - down.position.y) > viewConfiguration.touchSlop ||
                                    abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                                ) {
                                    return@awaitEachGesture
                                }
                                continue
                            }
                            if (!dragged && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                            if (dragged && change.positionChange() != Offset.Zero) {
                                change.consume()
                                last = change.position
                                val tick = g.tickAt(last.x, last.y)
                                when (hit) {
                                    is StaffHit.Body -> cb.move(hit.ref, (tick - hit.grabTicks).coerceAtLeast(0))
                                    is StaffHit.Edge -> cb.resize(hit.ref, tick)
                                    is StaffHit.Empty -> Unit
                                }
                            }
                        }
                        if (dragged) return@awaitEachGesture
                        when (hit) {
                            is StaffHit.Body -> cb.select(hit.ref)
                            is StaffHit.Edge -> cb.select(hit.ref)
                            is StaffHit.Empty -> {
                                val barMark = if (onLabel) {
                                    g.rows.getOrNull((down.position.y / g.metrics.rowHeight).toInt())?.bars?.lastOrNull { it.x <= down.position.x + edgePx }
                                } else {
                                    null
                                }
                                val target = armedNow
                                when {
                                    barMark != null && barMark.bar >= 0 -> cb.editBar(barMark.bar)
                                    target != null -> cb.place(target, hit.tick)
                                    else -> cb.select(null)
                                }
                            }
                        }
                    }
                },
        ) {
            drawStaff(geometry, selected, playheadTick, ghost, measurer)
        }
    }
}

private fun DrawScope.drawStaff(g: StaffGeometry, selected: SyllableRef?, playheadTick: Int?, ghost: Offset?, measurer: TextMeasurer) {
    val h = g.metrics.rowHeight
    val label = TextStyle(color = Palette.Muted, fontSize = 10.sp)
    val change = TextStyle(color = Palette.Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val syllable = TextStyle(color = Palette.CellText, fontSize = 12.sp)
    val accentStyle = TextStyle(color = Palette.StressedText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val stroke = 1.dp.toPx()
    for (row in g.rows) {
        val lineY = row.top + h * RowParts.LINE_Y
        drawLine(Palette.GridLine, Offset(g.metrics.leftPad, lineY), Offset(row.endX, lineY), strokeWidth = stroke * 1.5f)
        for (beat in row.beats) {
            drawLine(Palette.GridLine, Offset(beat.x, lineY - h * 0.05f), Offset(beat.x, lineY + h * 0.05f), strokeWidth = stroke)
        }
        for (bar in row.bars) {
            val pickup = bar.bar < 0
            drawLine(
                if (pickup) Palette.GridLine else Palette.Muted,
                Offset(bar.x, lineY - h * 0.16f),
                Offset(bar.x, lineY + h * 0.16f),
                strokeWidth = stroke,
                pathEffect = if (pickup) PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) else null,
            )
            val text = buildString {
                append(if (pickup) "pickup" else "${bar.bar + 1}")
                bar.meter?.let { append(" $it") }
                bar.tempo?.let { append(" $it") }
            }
            drawText(measurer, text, Offset(bar.x + 3f, row.top + h * RowParts.LABEL_Y), if (bar.meter != null || bar.tempo != null) change else label, maxLines = 1)
        }
        drawLine(Palette.Muted, Offset(row.endX, lineY - h * 0.16f), Offset(row.endX, lineY + h * 0.16f), strokeWidth = stroke)
    }
    for (mark in g.lines) {
        val top = g.rows[mark.row].top
        drawLine(Palette.RangeHighlight, Offset(mark.x, top + h * 0.2f), Offset(mark.x, top + h * 0.8f), strokeWidth = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
        drawText(measurer, "L${mark.line + 1}", Offset(mark.x + 2f, top + h * 0.78f), label.copy(color = Palette.RangeHighlight), maxLines = 1)
    }
    for (box in g.notes) {
        val top = g.rows[box.row].top
        val lineY = top + h * RowParts.LINE_Y
        val isSelected = box.ref == selected
        val color = if (isSelected) Palette.RangeHighlight else Palette.Primary
        val half = h * RowParts.NOTE_HALF
        drawRoundRect(
            color.copy(alpha = 0.55f),
            topLeft = Offset(box.left, lineY - half),
            size = Size((box.right - box.left).coerceAtLeast(2f), half * 2),
            cornerRadius = CornerRadius(3f),
        )
        if (box.head) {
            drawCircle(color, radius = half * 1.1f, center = Offset(box.left + half, lineY))
            if (box.accent > 0) {
                drawText(measurer, if (box.accent >= 2) ">>" else ">", Offset(box.left, top + h * RowParts.ACCENT_Y), accentStyle, maxLines = 1)
            }
            drawText(measurer, box.label, Offset(box.left, top + h * RowParts.SYLLABLE_Y), syllable, maxLines = 1, softWrap = false)
        }
        if (box.tail && isSelected) {
            drawRoundRect(Palette.StressedText, topLeft = Offset(box.right - 4f, lineY - half * 1.6f), size = Size(4f, half * 3.2f), cornerRadius = CornerRadius(2f))
        }
        if (isSelected) {
            drawRoundRect(Palette.SelectionBorder, topLeft = Offset(box.left - 2f, lineY - half - 2f), size = Size(box.right - box.left + 4f, half * 2 + 4f), cornerRadius = CornerRadius(4f), style = Stroke(stroke))
        }
    }
    playheadTick?.let { tick ->
        g.positionOf(tick)?.let { (row, x) ->
            drawLine(Palette.Fits, Offset(x, row.top + h * 0.15f), Offset(x, row.top + h * 0.85f), strokeWidth = stroke * 2)
        }
    }
    ghost?.let { drawCircle(Palette.Fits.copy(alpha = 0.7f), radius = h * 0.1f, center = it) }
}
