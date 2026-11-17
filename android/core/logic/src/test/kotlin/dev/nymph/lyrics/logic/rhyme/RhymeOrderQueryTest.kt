package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RhymeOrderQueryTest {
    private val a = snippet("a", rawText = "orange door hinge")
    private val selection = RhymeSelection(setOf(SnippetId("a")), mapOf(SnippetId("a") to a))

    private fun query(params: RhymeQueryParams, offset: Int = 0): RhymeSuggestRequest =
        (planRhymeQuery(CanvasId("c1"), params, selection, offset) as RhymeQueryPlan.Query).request

    @Test
    fun `rhyme order is the default and omits the field`() {
        assertEquals(RhymeOrder.RHYME, RhymeQueryParams().order)
        assertNull(query(RhymeQueryParams()).order)
    }

    @Test
    fun `meaning order is sent on every page`() {
        val meaning = RhymeQueryParams(order = RhymeOrder.MEANING)
        assertEquals(RhymeOrder.MEANING, query(meaning).order)
        assertEquals(RhymeOrder.MEANING, query(meaning, offset = 40).order)
    }

    @Test
    fun `the order is the only difference between the two queries`() {
        assertEquals(query(RhymeQueryParams()).copy(order = RhymeOrder.MEANING), query(RhymeQueryParams(order = RhymeOrder.MEANING)))
    }
}
