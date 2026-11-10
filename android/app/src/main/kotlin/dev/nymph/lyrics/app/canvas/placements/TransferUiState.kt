package dev.nymph.lyrics.app.canvas.placements

import dev.nymph.lyrics.logic.placements.pickerCanvases
import dev.nymph.lyrics.model.Canvas
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TransferMode

/** The user's canvases for the picker. */
sealed interface CanvasChoices {
    data object Loading : CanvasChoices

    data class Failed(val message: String) : CanvasChoices

    data class Loaded(val all: List<Canvas>) : CanvasChoices
}

/** The "New canvas…" title prompt inside the picker. */
data class NewCanvasPrompt(val error: String? = null)

sealed interface TransferUiState {
    data object Closed : TransferUiState

    /**
     * The picker for moving or also showing [ids] (never empty) from [source]. [busy] while a create
     * or transfer is in flight; [error] is the last failure, shown in the sheet.
     */
    data class Picking(
        val mode: TransferMode,
        val source: CanvasId,
        val ids: Set<SnippetId>,
        val choices: CanvasChoices = CanvasChoices.Loading,
        val query: String = "",
        val newCanvas: NewCanvasPrompt? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) : TransferUiState {
        init {
            require(ids.isNotEmpty())
        }

        /** The rows: other canvases matching [query], newest updated first; empty until loaded. */
        val rows: List<Canvas> by lazy {
            (choices as? CanvasChoices.Loaded)?.let { pickerCanvases(it.all, source, query) } ?: emptyList()
        }
    }
}

/** One-shot effects of the picker. */
sealed interface TransferEvent {
    /** The transfer happened: a snackbar with [text] and an Open action for [target]. */
    data class Done(val text: String, val target: CanvasId) : TransferEvent
}
