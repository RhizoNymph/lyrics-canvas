package dev.nymph.lyrics.app.canvas.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.SnippetId

/**
 * Feeds raw pointer events through [reduceGesture] and hands the resulting actions to
 * [onAction]. Positions are converted to dp before they reach the reducer.
 */
@Composable
fun Modifier.canvasGestures(hitTest: (Point) -> SnippetId?, onAction: (CanvasGestureAction) -> Unit): Modifier {
    val hit: State<(Point) -> SnippetId?> = rememberUpdatedState(hitTest)
    val act: State<(CanvasGestureAction) -> Unit> = rememberUpdatedState(onAction)
    return pointerInput(Unit) {
        val px = density
        fun Offset.dp() = Point((x / px).toDouble(), (y / px).toDouble())
        val slop = (viewConfiguration.touchSlop / px).toDouble()
        val longPressMs = viewConfiguration.longPressTimeoutMillis

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var state: GestureState = GestureState.Idle
            fun feed(input: GestureInput) {
                val step = reduceGesture(state, input, slop)
                state = step.state
                step.actions.forEach { act.value(it) }
            }
            val at = down.position.dp()
            feed(GestureInput.Down(at, hit.value(at)))
            val deadline = down.uptimeMillis + longPressMs
            var now = down.uptimeMillis
            var longPressSent = false

            try {
                trackGesture@ while (true) {
                val event: PointerEvent? = if (state is GestureState.Pressed && !longPressSent) {
                    withTimeoutOrNull((deadline - now).coerceAtLeast(0)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    longPressSent = true
                    feed(GestureInput.LongPressTimeout)
                    continue
                }
                now = event.changes.maxOfOrNull { it.uptimeMillis } ?: now
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    feed(GestureInput.Up)
                    break@trackGesture
                }
                if (pressed.size >= 2 || state is GestureState.Transforming) {
                    // A finger landing or lifting shifts the centroid; skip that frame's pan and zoom.
                    val settling = event.changes.any { it.changedToDown() || it.changedToUp() }
                    val pan = if (settling) Offset.Zero else event.calculatePan()
                    val zoom = if (settling) 1f else event.calculateZoom()
                    feed(GestureInput.Transform(event.calculateCentroid(useCurrent = true).dp(), pan.dp(), zoom.toDouble()))
                } else {
                    feed(GestureInput.Move(pressed.first().position.dp()))
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } finally {
                // Cancelled mid-gesture (e.g. the node was detached): still end a card move.
                if (state != GestureState.Idle) feed(GestureInput.Cancel)
            }
        }
    }
}
