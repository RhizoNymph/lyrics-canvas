package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TextAnchorRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryFromSelectionTest {
    private fun id(s: String) = SnippetId(s)
    private fun ids(vararg s: String) = s.map(::SnippetId).toCollection(LinkedHashSet())
    private fun whole(s: String) = SnippetAnchorRef(id(s), lineIndex = null)

    private val snippets = linkedMapOf(
        id("b") to snippet("b", "orange door hinge"),
        id("a") to snippet("a", "four inch"),
        id("empty") to snippet("empty", "   "),
    )

    @Test
    fun `is empty with nothing selected and no text`() {
        assertEquals(emptyList<Any>(), anchorsFromSelection(emptySet(), snippets, ""))
        assertEquals(emptyList<Any>(), anchorsFromSelection(emptySet(), snippets, "   "))
    }

    @Test
    fun `builds snippet anchors (last line) in id order and skips unknown or blank snippets`() {
        assertEquals(listOf(whole("a"), whole("b")), anchorsFromSelection(ids("b", "missing", "empty", "a"), snippets, ""))
    }

    @Test
    fun `appends a trimmed text anchor after snippet anchors`() {
        assertEquals(listOf(whole("a"), TextAnchorRef("door hinge")), anchorsFromSelection(ids("a"), snippets, "  door hinge "))
    }

    @Test
    fun `works with text only`() {
        assertEquals(listOf(TextAnchorRef("orange")), anchorsFromSelection(emptySet(), snippets, "orange"))
    }

    @Test
    fun `selectedSnippets returns present snippets in id order`() {
        assertEquals(listOf(id("a"), id("b")), selectedSnippets(ids("b", "zzz", "a"), snippets).map { it.id })
    }

    @Test
    fun `skips snippet anchors whose id was excluded for this query`() {
        assertEquals(listOf(whole("b"), TextAnchorRef("tin")), anchorsFromSelection(ids("a", "b"), snippets, "tin", ids("a")))
    }

    @Test
    fun `can exclude every snippet and leave only the typed anchor`() {
        assertEquals(listOf(TextAnchorRef("tin")), anchorsFromSelection(ids("a", "b"), snippets, "tin", ids("a", "b")))
    }

    @Test
    fun `anchorSnippets returns selected, non-blank, non-excluded snippets in id order`() {
        assertEquals(listOf(id("a"), id("b")), anchorSnippets(ids("b", "empty", "a", "zzz"), snippets, emptySet()).map { it.id })
        assertEquals(listOf(id("a")), anchorSnippets(ids("b", "a"), snippets, ids("b")).map { it.id })
    }

    private val ranged = mapOf(
        id("a") to snippet(
            "a",
            "I saw a black cat\norange door hinge",
            GridData(listOf(GridLine(emptyList(), 0, "I saw a black cat"), GridLine(emptyList(), 0, "orange door hinge"))),
        ),
        id("b") to snippet("b", "four inch"),
    )

    @Test
    fun `sends line_index, word_start and word_end for a snippet with a range`() {
        val ranges = mapOf(id("a") to AnchorRange(0, 3, 5))
        assertEquals(
            listOf(SnippetAnchorRef(id("a"), 0, 3, 5), whole("b")),
            anchorsFromSelection(ids("a", "b"), ranged, "", emptySet(), ranges),
        )
    }

    @Test
    fun `an excluded snippet sends nothing even with a range`() {
        val ranges = mapOf(id("a") to AnchorRange(0, 3, 5))
        assertEquals(listOf(TextAnchorRef("tin")), anchorsFromSelection(ids("a"), ranged, "tin", ids("a"), ranges))
    }
}
