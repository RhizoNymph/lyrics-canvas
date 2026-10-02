package dev.nymph.lyrics.app.canvas.gesture

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GestureReducerTest {
    private val card = SnippetId("s1")
    private val slop = 8.0

    private fun run(vararg inputs: GestureInput): Pair<GestureState, List<CanvasGestureAction>> {
        var state: GestureState = GestureState.Idle
        val actions = mutableListOf<CanvasGestureAction>()
        for (input in inputs) {
            val step = reduceGesture(state, input, slop)
            state = step.state
            actions += step.actions
        }
        return state to actions
    }

    @Test
    fun `down and up without moving is a tap on what was hit`() {
        val (state, actions) = run(GestureInput.Down(Point(10.0, 10.0), card), GestureInput.Move(Point(12.0, 11.0)), GestureInput.Up)
        assertEquals(GestureState.Idle, state)
        assertEquals(listOf(CanvasGestureAction.Tap(card)), actions)
    }

    @Test
    fun `tap on empty canvas reports no hit`() {
        val (_, actions) = run(GestureInput.Down(Point(0.0, 0.0), null), GestureInput.Up)
        assertEquals(listOf(CanvasGestureAction.Tap(null)), actions)
    }

    @Test
    fun `a short drag past the slop pans the canvas, even from a card`() {
        val (_, actions) = run(
            GestureInput.Down(Point(10.0, 10.0), card),
            GestureInput.Move(Point(30.0, 10.0)),
            GestureInput.Move(Point(35.0, 15.0)),
            GestureInput.Up,
        )
        assertEquals(
            listOf(CanvasGestureAction.Pan(Point(20.0, 0.0)), CanvasGestureAction.Pan(Point(5.0, 5.0))),
            actions,
        )
    }

    @Test
    fun `a long press on a card starts a move that ends on release`() {
        val (state, actions) = run(
            GestureInput.Down(Point(10.0, 10.0), card),
            GestureInput.LongPressTimeout,
            GestureInput.Move(Point(15.0, 10.0)),
            GestureInput.Move(Point(25.0, 30.0)),
            GestureInput.Up,
        )
        assertEquals(GestureState.Idle, state)
        assertEquals(
            listOf(
                CanvasGestureAction.LongPress(card),
                CanvasGestureAction.MoveCard(card, Point(5.0, 0.0)),
                CanvasGestureAction.MoveCard(card, Point(10.0, 20.0)),
                CanvasGestureAction.EndMove(card),
            ),
            actions,
        )
    }

    @Test
    fun `a long press without moving still ends the move`() {
        val (_, actions) = run(GestureInput.Down(Point(10.0, 10.0), card), GestureInput.LongPressTimeout, GestureInput.Up)
        assertEquals(listOf(CanvasGestureAction.LongPress(card), CanvasGestureAction.EndMove(card)), actions)
    }

    @Test
    fun `cancel during a move still ends it`() {
        val (_, actions) = run(GestureInput.Down(Point(10.0, 10.0), card), GestureInput.LongPressTimeout, GestureInput.Cancel)
        assertEquals(listOf(CanvasGestureAction.LongPress(card), CanvasGestureAction.EndMove(card)), actions)
    }

    @Test
    fun `a long press on empty canvas does nothing and a later drag pans`() {
        val (_, actions) = run(
            GestureInput.Down(Point(10.0, 10.0), null),
            GestureInput.LongPressTimeout,
            GestureInput.Move(Point(10.0, 40.0)),
            GestureInput.Up,
        )
        assertEquals(listOf(CanvasGestureAction.Pan(Point(0.0, 30.0))), actions)
    }

    @Test
    fun `the long-press timeout after panning started is ignored`() {
        val (_, actions) = run(
            GestureInput.Down(Point(0.0, 0.0), card),
            GestureInput.Move(Point(20.0, 0.0)),
            GestureInput.LongPressTimeout,
            GestureInput.Up,
        )
        assertEquals(listOf(CanvasGestureAction.Pan(Point(20.0, 0.0))), actions)
    }

    @Test
    fun `a second finger switches to transform until all fingers lift`() {
        val transform = GestureInput.Transform(Point(50.0, 50.0), Point(1.0, 2.0), 1.5)
        val (state, actions) = run(
            GestureInput.Down(Point(10.0, 10.0), card),
            transform,
            GestureInput.Transform(Point(50.0, 50.0), Point(0.0, 0.0), 0.5),
            GestureInput.Up,
        )
        assertEquals(GestureState.Idle, state)
        assertEquals(
            listOf(
                CanvasGestureAction.Transform(Point(50.0, 50.0), Point(1.0, 2.0), 1.5),
                CanvasGestureAction.Transform(Point(50.0, 50.0), Point(0.0, 0.0), 0.5),
            ),
            actions,
        )
    }

    @Test
    fun `a second finger during a card move is ignored`() {
        val (state, actions) = run(
            GestureInput.Down(Point(10.0, 10.0), card),
            GestureInput.LongPressTimeout,
            GestureInput.Transform(Point(0.0, 0.0), Point(3.0, 3.0), 2.0),
        )
        assertEquals(GestureState.Dragging(card, Point(10.0, 10.0)), state)
        assertEquals(listOf(CanvasGestureAction.LongPress(card)), actions)
    }

    @Test
    fun `transform from panning keeps panning delta before it`() {
        val (state, actions) = run(
            GestureInput.Down(Point(0.0, 0.0), null),
            GestureInput.Move(Point(0.0, 10.0)),
            GestureInput.Transform(Point(5.0, 5.0), Point(0.0, 0.0), 2.0),
        )
        assertEquals(GestureState.Transforming, state)
        assertEquals(
            listOf(CanvasGestureAction.Pan(Point(0.0, 10.0)), CanvasGestureAction.Transform(Point(5.0, 5.0), Point(0.0, 0.0), 2.0)),
            actions,
        )
    }

    @Test
    fun `a stray down while a gesture is running is ignored`() {
        val pressed = GestureState.Pressed(Point(0.0, 0.0), card)
        assertEquals(GestureStep(pressed, emptyList()), reduceGesture(pressed, GestureInput.Down(Point(5.0, 5.0), null), slop))
    }

    @Test
    fun `input while idle other than down is ignored`() {
        assertEquals(GestureStep(GestureState.Idle, emptyList()), reduceGesture(GestureState.Idle, GestureInput.Up, slop))
        assertEquals(GestureStep(GestureState.Idle, emptyList()), reduceGesture(GestureState.Idle, GestureInput.Move(Point(1.0, 1.0)), slop))
    }
}
