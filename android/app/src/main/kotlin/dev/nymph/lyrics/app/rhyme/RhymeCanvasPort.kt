package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlinx.coroutines.flow.Flow

/** What the rhyme sheet reads from the canvas: the selection and the camera (for placement). */
data class RhymeCanvasView(
    val canvasId: CanvasId,
    val selectedIds: Set<SnippetId>,
    val snippets: Map<SnippetId, Snippet>,
    val viewport: Viewport,
    val viewSize: ViewSize,
) {
    /** The selected snippets that exist; the part of the canvas a query depends on. */
    val selectedSnippets: Map<SnippetId, Snippet> get() = snippets.filterKeys { it in selectedIds }
}

/**
 * The canvas as seen by the rhyme sheet. Implemented by the canvas ViewModel; the sheet never
 * changes the canvas except through [insertMaterialized] with a server-confirmed snippet.
 */
interface RhymeCanvasPort {
    /** Null until the canvas has loaded. */
    val rhymeView: Flow<RhymeCanvasView?>

    fun currentRhymeView(): RhymeCanvasView?

    fun insertMaterialized(snippet: Snippet)
}
