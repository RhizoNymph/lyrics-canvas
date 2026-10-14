package dev.nymph.lyrics.app.canvas

import dev.nymph.lyrics.logic.canvas.MergeError
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.logic.list.ListOrder
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

/** Which way the canvas screen shows its snippets; the persisted (saved-state) form of [CanvasMode]. */
enum class CanvasModeKind {
    CANVAS,
    LIST,
    ;

    companion object {
        /** A saved value, or [CANVAS] for anything unknown. */
        fun parse(saved: String?): CanvasModeKind = entries.firstOrNull { it.name == saved } ?: CANVAS
    }
}

/** The canvas screen's body: the spatial canvas, or the snippet list with the order captured when it was (re)sorted. */
sealed interface CanvasMode {
    val kind: CanvasModeKind

    data object Canvas : CanvasMode {
        override val kind = CanvasModeKind.CANVAS
    }

    data class SnippetList(val order: ListOrder) : CanvasMode {
        override val kind = CanvasModeKind.LIST
    }
}

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
        val mode: CanvasMode = CanvasMode.Canvas,
    ) : CanvasUiState {
        val byId: Map<SnippetId, Snippet> = snippets.associateBy { it.id }

        /** List-mode rows ([ListOrder.arrange] of [snippets]); empty on the canvas. Computed on first read. */
        val listRows: List<Snippet> by lazy {
            when (mode) {
                CanvasMode.Canvas -> emptyList()
                is CanvasMode.SnippetList -> mode.order.arrange(snippets)
            }
        }
    }
}

/** One-shot effects for the screen. */
sealed interface CanvasEvent {
    data class OpenEditor(val snippetId: SnippetId) : CanvasEvent

    /** A snackbar message. */
    data class Message(val text: String) : CanvasEvent
}
