package dev.nymph.lyrics.app.canvas

import dev.nymph.lyrics.logic.canvas.MergeError
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId

/** Selection mode. [Selecting] always holds at least one id; deselecting the last one leaves the mode. */
sealed interface Selection {
    data object Idle : Selection

    @ConsistentCopyVisibility
    data class Selecting private constructor(val ids: Set<SnippetId>) : Selection {
        companion object {
            internal fun create(ids: Set<SnippetId>): Selecting {
                require(ids.isNotEmpty())
                return Selecting(ids)
            }
        }
    }

    companion object {
        fun of(ids: Set<SnippetId>): Selection = if (ids.isEmpty()) Idle else Selecting.create(ids)
    }
}

val Selection.ids: Set<SnippetId> get() = (this as? Selection.Selecting)?.ids ?: emptySet()

sealed interface CanvasUiState {
    data object Loading : CanvasUiState

    data class Failed(val message: String) : CanvasUiState

    data class Ready(
        val canvasId: CanvasId,
        val title: String,
        /** Server-confirmed snippets in load / insertion order (drawn by z-index). */
        val snippets: List<Snippet>,
        val viewport: Viewport,
        /** The canvas element's size in dp (0 until laid out). */
        val viewSize: ViewSize,
        val selection: Selection = Selection.Idle,
        /** A merge or delete is in flight; the selection actions are disabled. */
        val busy: Boolean = false,
        /** The last merge failure, cleared by the next successful merge or a selection exit. */
        val mergeError: MergeError? = null,
    ) : CanvasUiState {
        val byId: Map<SnippetId, Snippet> = snippets.associateBy { it.id }
    }
}

/** One-shot effects for the screen. */
sealed interface CanvasEvent {
    data class OpenEditor(val snippetId: SnippetId) : CanvasEvent

    /** A snackbar message. */
    data class Message(val text: String) : CanvasEvent
}
