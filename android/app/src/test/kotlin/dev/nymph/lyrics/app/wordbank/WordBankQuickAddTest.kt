package dev.nymph.lyrics.app.wordbank

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.logic.wordbank.isValidWordText
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class WordBankQuickAddTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repo = FakeWordBankRepository(listOf(bankWord("night")))

    @Test
    fun `a selection is trimmed, split and posted without tags`() = runTest(main.dispatcher) {
        val vm = WordBankQuickAddViewModel(repo)
        vm.messages.test {
            vm.add("  neon lights,\nNight  ")
            runCurrent()
            assertEquals("Added 1, 1 already in bank", awaitItem())
        }
        assertEquals(listOf("neon lights", "Night") to emptyList<Any>(), repo.adds.single())
    }

    @Test
    fun `invalid text is refused locally`() = runTest(main.dispatcher) {
        val vm = WordBankQuickAddViewModel(repo)
        vm.messages.test {
            vm.add("verse 2")
            runCurrent()
            assertEquals("Word 1 (\"verse 2\"): \"2\" is not allowed: use letters, apostrophes, hyphens and spaces.", awaitItem())
        }
        assertTrue(repo.adds.isEmpty())
    }

    @Test
    fun `blank selections do nothing and server errors are typed`() = runTest(main.dispatcher) {
        repo.addResult = { _, _ -> ApiResult.Failure(ApiError.Unauthorized(401, null)) }
        val vm = WordBankQuickAddViewModel(repo)
        vm.messages.test {
            vm.add(" , ")
            vm.add("rain")
            runCurrent()
            assertEquals("Your session has expired. Sign in again.", awaitItem())
        }
        assertEquals(1, repo.adds.size)
    }

    @Test
    fun `rhyme candidates are enabled only when valid as a word`() {
        assertTrue(isValidWordText("tin"))
        assertTrue(isValidWordText("in the night"))
        assertFalse(isValidWordText("route 66"))
        assertFalse(isValidWordText("a".repeat(65)))
    }
}
