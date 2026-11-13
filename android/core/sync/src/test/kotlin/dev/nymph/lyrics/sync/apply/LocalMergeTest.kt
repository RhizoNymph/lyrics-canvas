package dev.nymph.lyrics.sync.apply

import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.GridMode
import dev.nymph.lyrics.model.GroupId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Cases from backend/tests for merge_service, run against the port. */
class LocalMergeTest {
    private fun cell(s: String, w: Int) = GridCell(syllable = s, wordIndex = w)

    private fun source(
        id: String,
        x: Double,
        y: Double,
        text: String,
        grid: GridData? = null,
        mode: GridMode = GridMode.AUTO,
        beats: Int? = null,
        type: SnippetType = SnippetType.VERSE,
        tags: List<String> = emptyList(),
        group: String? = null,
    ) = MergeSource(SnippetId(id), text, grid, type, mode, beats, group?.let(::GroupId), x, y, tags.map(::TagId))

    @Test
    fun `fewer than two distinct sources is no merge`() {
        val a = source("a", 0.0, 0.0, "x")
        assertNull(LocalMerge.merge(listOf(a, a), MergeMode.VERTICAL))
    }

    @Test
    fun `vertical stacks in y then x order and the first block supplies type, position and group`() {
        val top = source("b", 50.0, 0.0, "top", type = SnippetType.CHORUS, group = "g")
        val bottom = source("a", 0.0, 10.0, "bottom\n\n  more  ")
        val m = checkNotNull(LocalMerge.merge(listOf(bottom, top), MergeMode.VERTICAL))
        assertEquals("top\nbottom\nmore", m.rawText)
        assertEquals(SnippetType.CHORUS, m.snippetType)
        assertEquals(50.0, m.positionX)
        assertEquals(GroupId("g"), m.groupId)
        assertEquals(listOf(SnippetId("b"), SnippetId("a")), m.sourceIds)
    }

    @Test
    fun `ties break by id`() {
        val m = checkNotNull(LocalMerge.merge(listOf(source("b", 0.0, 0.0, "B"), source("a", 0.0, 0.0, "A")), MergeMode.VERTICAL))
        assertEquals("A\nB", m.rawText)
    }

    @Test
    fun `horizontal joins line by line and re-bases word indexes by the words already on the line`() {
        val left = source("l", 0.0, 0.0, "hey you", GridData(listOf(GridLine(listOf(cell("hey", 0), cell("you", 1)), 2, "hey you"))))
        val right = source("r", 100.0, 0.0, "there\nsecond", GridData(listOf(GridLine(listOf(cell("there", 0)), 1, "there"), GridLine(listOf(cell("sec", 0), cell("ond", 0)), 2, "second"))))
        val m = checkNotNull(LocalMerge.merge(listOf(right, left), MergeMode.HORIZONTAL))
        assertEquals("hey you there\nsecond", m.rawText)
        assertEquals(listOf(0, 1, 2), m.grid.lines[0].cells.map { it.wordIndex })
        assertEquals(3, m.grid.lines[0].syllableCount)
        assertEquals(listOf(0, 0), m.grid.lines[1].cells.map { it.wordIndex })
    }

    @Test
    fun `rest marks do not count as words when re-basing`() {
        val left = source("l", 0.0, 0.0, "a _ b", GridData(listOf(GridLine(listOf(cell("a", 0), GridCell.REST, cell("b", 1)), 2, "a _ b"))))
        val right = source("r", 10.0, 0.0, "c", GridData(listOf(GridLine(listOf(cell("c", 0)), 1, "c"))))
        val m = checkNotNull(LocalMerge.merge(listOf(left, right), MergeMode.HORIZONTAL))
        assertEquals(listOf(0, null, 1, 2), m.grid.lines[0].cells.map { it.wordIndex })
    }

    @Test
    fun `fixed grids pad with rests and raise beats, never truncate`() {
        val a = source("a", 0.0, 0.0, "one", GridData(listOf(GridLine(listOf(cell("one", 0)), 1, "one"))), mode = GridMode.FIXED, beats = 2)
        val b = source("b", 0.0, 1.0, "a b c", GridData(listOf(GridLine(listOf(cell("a", 0), cell("b", 1), cell("c", 2)), 3, "a b c"))))
        val m = checkNotNull(LocalMerge.merge(listOf(a, b), MergeMode.VERTICAL))
        assertEquals(3, m.beatsPerLine)
        assertEquals(listOf(3, 3), m.grid.lines.map { it.cells.size })
        assertEquals(true, m.grid.lines[0].cells[2].rest)
    }

    @Test
    fun `tags are the union in first-seen order`() {
        val m = checkNotNull(LocalMerge.merge(listOf(source("a", 0.0, 0.0, "x", tags = listOf("t2", "t1")), source("b", 0.0, 1.0, "y", tags = listOf("t1", "t3"))), MergeMode.VERTICAL))
        assertEquals(listOf("t2", "t1", "t3"), m.tagIds.map { it.value })
    }
}
