package dev.nymph.lyrics.app.canvas.placements

import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.TransferResponse

/** What the transfer picker needs from the canvas screen (implemented by CanvasViewModel). */
interface PlacementCanvasPort {
    /** A server-confirmed transfer to [target]: a move takes the snippets off, a show updates their badges. */
    fun onTransferred(response: TransferResponse, target: CanvasRef)
}
