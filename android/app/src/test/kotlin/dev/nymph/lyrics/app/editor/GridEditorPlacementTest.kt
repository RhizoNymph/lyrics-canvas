package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.model.CanvasId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Every editor save names the canvas the snippet was opened on (`PUT /api/snippets/{id}?canvas_id=`). */
@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorPlacementTest {
    private val nlp = FakeNlpRepository()
    private val repo = FakeSnippetRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save and flush send the snippet's canvas`() = runTest {
        val opened = snippet("one line", serverGrid("one line")).copy(canvasId = CanvasId("c7"))
        val vm = GridEditorViewModel(opened, nlp, repo).also { runCurrent() }
        advanceUntilIdle()
        vm.typeLine(0, "one line more")
        vm.flushPendingEdits()
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf(CanvasId("c7"), CanvasId("c7")), repo.updateCanvases)
    }
}
