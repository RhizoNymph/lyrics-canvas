package dev.nymph.lyrics.app.canvas

import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.canvas.list.listCardHeight
import dev.nymph.lyrics.app.canvas.render.CardMetrics
import dev.nymph.lyrics.app.canvas.render.fallbackTextOf
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.app.rhyme.chipLabel
import dev.nymph.lyrics.app.wordbank.FakeWordBankRepository
import dev.nymph.lyrics.app.wordbank.WordBankQuickAddViewModel
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** Text consumers on Android read display text (rest marks stripped). */
@OptIn(ExperimentalCoroutinesApi::class)
class RestConsumersTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @Test
    fun `the card text fallback shows display text and a rests-only text shows nothing`() {
        assertEquals("go now", fallbackTextOf(snippet("a", rawText = "go_now")))
        assertNull(fallbackTextOf(snippet("a", rawText = "_ _")))
        assertEquals(CardMetrics.HEADER_HEIGHT + CardMetrics.CELL_HEIGHT, listCardHeight(snippet("a", rawText = "_"), 0.0))
    }

    @Test
    fun `rhyme chips show display text`() {
        val line = GridLine(
            listOf(GridCell(syllable = "I", wordIndex = 0), GridCell.REST, GridCell(syllable = "go", wordIndex = 1)),
            2,
            "I _ go",
        )
        val s = snippet("a", rawText = "I _ go", lines = listOf(line))
        assertEquals("I go", chipLabel(s, null))
        assertEquals("go", chipLabel(s, AnchorRange(0, 1, 2)))
    }

    @Test
    fun `word bank add from snippet text drops rest marks`() = runTest(main.dispatcher) {
        val repo = FakeWordBankRepository(emptyList())
        val vm = WordBankQuickAddViewModel(repo)
        vm.add("neon_lights, _")
        runCurrent()
        assertEquals(listOf("neon lights"), repo.adds.single().first)
    }
}
