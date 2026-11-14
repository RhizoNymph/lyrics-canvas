package dev.nymph.lyrics.logic.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.TARGET
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFx.board
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.model.RhymeOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PatternOrderQueryTest {
    private fun query(params: PatternParams, page: PatternPage = PatternPage.First) =
        (planPatternSuggest(TARGET, board(), params, ExpressionField(), emptySet(), page) as PatternPlan.Query).request

    @Test
    fun `rhyme order sends no order field`() {
        assertNull(query(PatternParams()).order)
    }

    @Test
    fun `meaning order is sent on the first page and on every slot or line page`() {
        val meaning = PatternParams(order = RhymeOrder.MEANING)
        assertEquals(RhymeOrder.MEANING, query(meaning).order)
        assertEquals(RhymeOrder.MEANING, query(meaning, PatternPage.Slot(SlotId("s0"), 30)).order)
        assertEquals(RhymeOrder.MEANING, query(meaning, PatternPage.LineMatches(30)).order)
    }

    @Test
    fun `changing the order changes the query`() {
        val rhyme = query(PatternParams())
        val meaning = query(PatternParams(order = RhymeOrder.MEANING))
        assertEquals(rhyme.copy(order = RhymeOrder.MEANING), meaning)
    }
}
