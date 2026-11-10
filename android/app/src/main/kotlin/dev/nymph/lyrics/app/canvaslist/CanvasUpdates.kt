package dev.nymph.lyrics.app.canvaslist

import dev.nymph.lyrics.model.Canvas
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Canvases renamed or created away from the canvas list (the canvas screen's title, the transfer
 * picker's "New canvas…"), for the list below on the back stack, so it updates without a reload.
 * A broadcast channel, one per process; nothing is kept for late subscribers (their own list load
 * is newer).
 */
class CanvasUpdates {
    private val flow = MutableSharedFlow<Canvas>(extraBufferCapacity = 64)

    val updates: SharedFlow<Canvas> = flow.asSharedFlow()

    fun publish(canvas: Canvas) {
        flow.tryEmit(canvas)
    }
}
