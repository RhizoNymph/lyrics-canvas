package dev.nymph.lyrics.logic.grid

import dev.nymph.lyrics.logic.syls
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MoveChunkTest {
    private fun c(syllable: String, wordIndex: Int) = GridCell(syllable = syllable, wordIndex = wordIndex)
    private fun rest() = GridCell(rest = true)
    private fun line(cells: List<GridCell>, rawText: String = "") = GridLine(cells, cells.count { !it.isRest }, rawText)
    private fun idx(l: GridLine) = l.cells.map { it.wordIndex }

    // "hel lo · world"
    private val base = line(listOf(c("hel", 0), c("lo", 0), rest(), c("world", 1)), "hello world")

    @Test
    fun `moves a chunk later past a rest (shifts timing)`() {
        val out = moveChunk(base, 0, 2, 1)
        assertEquals(listOf("·", "hel", "lo", "world"), syls(out))
        assertEquals(listOf(null, 0, 0, 1), idx(out))
    }

    @Test
    fun `moves a whole word past another word and renumbers`() {
        val out = moveChunk(base, 3, 4, 0)
        assertEquals(listOf("world", "hel", "lo", "·"), syls(out))
        assertEquals(listOf(0, 1, 1, null), idx(out))
    }

    @Test
    fun `keeps one index per word when a syllable is dragged past another word`() {
        val out = moveChunk(base, 1, 2, 3)
        assertEquals(listOf("hel", "·", "world", "lo"), syls(out))
        assertEquals(listOf(0, null, 1, 0), idx(out))
    }

    @Test
    fun `recomputes syllable_count from non-rest cells`() {
        assertEquals(3, moveChunk(base, 2, 3, 0).syllableCount)
    }

    @Test
    fun `clamps the target into range`() {
        assertEquals(listOf("lo", "·", "world", "hel"), syls(moveChunk(base, 0, 1, 99)))
        assertEquals(listOf("world", "hel", "lo", "·"), syls(moveChunk(base, 3, 4, -5)))
    }

    @Test
    fun `is a no-op for an empty or out-of-range chunk`() {
        assertSame(base, moveChunk(base, 2, 2, 0))
        assertSame(base, moveChunk(base, 5, 7, 0))
        assertSame(base, moveChunk(base, -1, 0, 0))
    }

    @Test
    fun `moving to the same place keeps the order`() {
        assertEquals(syls(base), syls(moveChunk(base, 1, 3, 1)))
    }

    @Test
    fun `does not mutate the input line`() {
        moveChunk(base, 0, 1, 3)
        assertEquals(listOf("hel", "lo", "·", "world"), syls(base))
    }

    @Test
    fun `leaves raw_text untouched (text is rebuilt separately)`() {
        assertEquals("hello world", moveChunk(base, 3, 4, 0).rawText)
    }
}
