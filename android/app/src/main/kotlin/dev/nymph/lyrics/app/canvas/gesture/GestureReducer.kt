package dev.nymph.lyrics.app.canvas.gesture

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.SnippetId
import kotlin.math.hypot

/**
 * The canvas touch state machine, as a pure reducer (positions in screen dp):
 * - down then up within the touch slop: tap on whatever was under the finger;
 * - one finger past the slop before the long-press timeout: pan the canvas (also from a card);
 * - long-press timeout on a card: long press (selection), then moves drag that card until release;
 * - a second finger (outside a card drag): transform (pan + pinch zoom about the centroid) until all fingers lift.
 */
sealed interface GestureState {
    data object Idle : GestureState

    /** A finger is down and has not left the slop; [last] is its latest position. */
    data class Pressed(val start: Point, val hit: SnippetId?, val last: Point = start) : GestureState

    data class Panning(val last: Point) : GestureState

    data class Dragging(val id: SnippetId, val last: Point) : GestureState

    data object Transforming : GestureState
}

sealed interface GestureInput {
    /** First finger down; [hit] is the card under it, if any. */
    data class Down(val position: Point, val hit: SnippetId?) : GestureInput

    /** The single finger moved. */
    data class Move(val position: Point) : GestureInput

    /** The finger stayed down past the long-press timeout without leaving the slop. */
    data object LongPressTimeout : GestureInput

    /** A multi-finger frame (or any frame once transforming): centroid, pan since last frame, zoom factor. */
    data class Transform(val centroid: Point, val pan: Point, val zoom: Double) : GestureInput

    /** All fingers lifted. */
    data object Up : GestureInput

    /** The gesture was cancelled (e.g. consumed elsewhere). */
    data object Cancel : GestureInput
}

sealed interface CanvasGestureAction {
    data class Tap(val hit: SnippetId?) : CanvasGestureAction

    data class LongPress(val id: SnippetId) : CanvasGestureAction

    data class Pan(val delta: Point) : CanvasGestureAction

    data class MoveCard(val id: SnippetId, val screenDelta: Point) : CanvasGestureAction

    data class EndMove(val id: SnippetId) : CanvasGestureAction

    data class Transform(val centroid: Point, val pan: Point, val zoom: Double) : CanvasGestureAction
}

data class GestureStep(val state: GestureState, val actions: List<CanvasGestureAction>)

private fun Point.minus(other: Point): Point = Point(x - other.x, y - other.y)

private fun stay(state: GestureState) = GestureStep(state, emptyList())

fun reduceGesture(state: GestureState, input: GestureInput, touchSlop: Double): GestureStep = when (state) {
    GestureState.Idle -> when (input) {
        is GestureInput.Down -> stay(GestureState.Pressed(input.position, input.hit))
        else -> stay(state)
    }

    is GestureState.Pressed -> when (input) {
        is GestureInput.Down -> stay(state)
        is GestureInput.Move -> {
            val moved = hypot(input.position.x - state.start.x, input.position.y - state.start.y)
            if (moved > touchSlop) {
                GestureStep(GestureState.Panning(input.position), listOf(CanvasGestureAction.Pan(input.position.minus(state.start))))
            } else {
                stay(state.copy(last = input.position))
            }
        }
        GestureInput.LongPressTimeout -> if (state.hit != null) {
            GestureStep(GestureState.Dragging(state.hit, state.last), listOf(CanvasGestureAction.LongPress(state.hit)))
        } else {
            stay(state)
        }
        is GestureInput.Transform -> GestureStep(GestureState.Transforming, listOf(input.toAction()))
        GestureInput.Up -> GestureStep(GestureState.Idle, listOf(CanvasGestureAction.Tap(state.hit)))
        GestureInput.Cancel -> stay(GestureState.Idle)
    }

    is GestureState.Panning -> when (input) {
        is GestureInput.Move -> GestureStep(GestureState.Panning(input.position), listOf(CanvasGestureAction.Pan(input.position.minus(state.last))))
        is GestureInput.Transform -> GestureStep(GestureState.Transforming, listOf(input.toAction()))
        GestureInput.Up, GestureInput.Cancel -> stay(GestureState.Idle)
        is GestureInput.Down, GestureInput.LongPressTimeout -> stay(state)
    }

    is GestureState.Dragging -> when (input) {
        is GestureInput.Move -> GestureStep(
            GestureState.Dragging(state.id, input.position),
            listOf(CanvasGestureAction.MoveCard(state.id, input.position.minus(state.last))),
        )
        GestureInput.Up, GestureInput.Cancel -> GestureStep(GestureState.Idle, listOf(CanvasGestureAction.EndMove(state.id)))
        is GestureInput.Down, GestureInput.LongPressTimeout, is GestureInput.Transform -> stay(state)
    }

    GestureState.Transforming -> when (input) {
        is GestureInput.Transform -> GestureStep(state, listOf(input.toAction()))
        GestureInput.Up, GestureInput.Cancel -> stay(GestureState.Idle)
        is GestureInput.Down, is GestureInput.Move, GestureInput.LongPressTimeout -> stay(state)
    }
}

private fun GestureInput.Transform.toAction() = CanvasGestureAction.Transform(centroid, pan, zoom)
