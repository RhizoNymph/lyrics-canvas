package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeCanvasPort(snippets: List<Snippet>, selected: Set<SnippetId>) : RhymeCanvasPort {
    val view = MutableStateFlow<RhymeCanvasView?>(
        RhymeCanvasView(CANVAS_ID, selected, snippets.associateBy { it.id }, Viewport.IDENTITY, ViewSize(400.0, 800.0)),
    )
    val inserted = mutableListOf<Snippet>()

    override val rhymeView: StateFlow<RhymeCanvasView?> get() = view

    override fun currentRhymeView(): RhymeCanvasView? = view.value

    override fun insertMaterialized(snippet: Snippet) {
        inserted += snippet
        val v = view.value ?: return
        view.value = v.copy(snippets = v.snippets + (snippet.id to snippet))
    }

    fun select(ids: Set<SnippetId> = emptySet()) {
        view.value = view.value!!.copy(selectedIds = ids)
    }
}
