package dev.nymph.lyrics.app.session

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.CanvasUiState
import dev.nymph.lyrics.app.canvas.CanvasViewModel
import dev.nymph.lyrics.app.canvaslist.CanvasListUiState
import dev.nymph.lyrics.app.canvaslist.CanvasListViewModel
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.FakeAuthRepository
import dev.nymph.lyrics.app.fakes.FakeCanvasRepository
import dev.nymph.lyrics.app.fakes.FakeSnippetRepository
import dev.nymph.lyrics.app.fakes.UNAUTHORIZED
import dev.nymph.lyrics.data.InMemoryTokenStore
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @Test
    fun `session follows the token store`() = runTest(main.dispatcher) {
        val tokens = InMemoryTokenStore()
        val vm = SessionViewModel(tokens, FakeAuthRepository(tokens))
        assertEquals(SessionState.Unknown, vm.state.value)
        runCurrent()
        assertEquals(SessionState.SignedOut, vm.state.value)
        tokens.save("t")
        runCurrent()
        assertEquals(SessionState.SignedIn, vm.state.value)
    }

    @Test
    fun `sign out clears the token`() = runTest(main.dispatcher) {
        val tokens = InMemoryTokenStore("t")
        val vm = SessionViewModel(tokens, FakeAuthRepository(tokens))
        runCurrent()
        vm.signOut()
        runCurrent()
        assertEquals(SessionState.SignedOut, vm.state.value)
    }

    @Test
    fun `a 401 while loading a canvas signs the session out`() = runTest(main.dispatcher) {
        val tokens = InMemoryTokenStore("t")
        val session = SessionViewModel(tokens, FakeAuthRepository(tokens))
        runCurrent()
        assertEquals(SessionState.SignedIn, session.state.value)
        val canvas = CanvasViewModel(
            CANVAS_ID,
            FakeCanvasRepository(ApiResult.Failure(UNAUTHORIZED), tokens),
            FakeSnippetRepository(tokens),
            persistScope = backgroundScope,
        )
        runCurrent()
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(CanvasUiState.Failed("Your session has expired. Sign in again."), canvas.state.value)
    }

    @Test
    fun `a 401 on the canvas list signs the session out`() = runTest(main.dispatcher) {
        val tokens = InMemoryTokenStore("t")
        val session = SessionViewModel(tokens, FakeAuthRepository(tokens))
        val repo = FakeCanvasRepository(ApiResult.Failure(UNAUTHORIZED), tokens).apply { listResult = ApiResult.Failure(UNAUTHORIZED) }
        val list = CanvasListViewModel(repo)
        runCurrent()
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(CanvasListUiState.Failed("Your session has expired. Sign in again."), list.state.value)
    }
}
