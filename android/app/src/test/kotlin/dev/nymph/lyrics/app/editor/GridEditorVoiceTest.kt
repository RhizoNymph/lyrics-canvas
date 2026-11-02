package dev.nymph.lyrics.app.editor

import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The editor's side of a voice take: save pending edits first, then take the returned rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class GridEditorVoiceTest {
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

    private val saved = snippet("one line\ntwo line", serverGrid("one line\ntwo line"))

    private fun TestScope.settled(): GridEditorViewModel = GridEditorViewModel(saved, nlp, repo).also {
        runCurrent()
        advanceUntilIdle()
    }

    @Test
    fun `nothing edited - flush answers the opened snippet without a request`() = runTest {
        val vm = settled()
        assertEquals(ApiResult.Success(saved), vm.flushPendingEdits())
        assertTrue(repo.updates.isEmpty())
    }

    @Test
    fun `pending edits are saved without closing the editor`() = runTest {
        val vm = settled()
        vm.typeLine(1, "two lines")
        val result = vm.flushPendingEdits()
        assertEquals(1, repo.updates.size)
        assertEquals("one line\ntwo lines", repo.updates[0].second.rawText)
        assertEquals("one line\ntwo lines", (result as ApiResult.Success).value.rawText)
        assertEquals(SaveStatus.Idle, vm.state.value.save)
        // A second flush with nothing new is free.
        vm.flushPendingEdits()
        assertEquals(1, repo.updates.size)
    }

    @Test
    fun `a failed flush reports the error and keeps the edits`() = runTest {
        val vm = settled()
        vm.typeLine(0, "changed")
        repo.respond = { _, _ -> ApiResult.Failure(ApiError.Server(500, "down")) }
        val result = vm.flushPendingEdits()
        assertInstanceOf(ApiResult.Failure::class.java, result)
        assertEquals("changed", vm.state.value.rows.lines[0])
        assertEquals(SaveStatus.Idle, vm.state.value.save)
    }

    @Test
    fun `reloadFrom replaces the rows with the appended snippet`() = runTest {
        val vm = settled()
        vm.typeLine(0, "stale")
        val appended = snippet("one line\ntwo line\nthree line", serverGrid("one line\ntwo line\nthree line"))
        vm.reloadFrom(appended)
        assertEquals(listOf("one line", "two line", "three line"), vm.state.value.rows.lines)
        assertEquals(null, vm.state.value.selection)
        // The appended snippet is the new baseline: nothing to flush.
        assertEquals(ApiResult.Success(appended), vm.flushPendingEdits())
        assertTrue(repo.updates.isEmpty())
    }
}
