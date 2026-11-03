package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.model.CanvasId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** "Only my lines matching": the normalized expression is sent; a known-invalid one blocks the query. */
class RhymeTagFilterPlanTest {
    private val c1 = CanvasId("c1")
    private val typed = RhymeSelection(emptySet(), emptyMap(), typedText = "door hinge")

    @Test
    fun `no filter sends no tag_filter`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), typed, 0) as RhymeQueryPlan.Query
        assertNull(plan.request.tagFilter)
    }

    @Test
    fun `a valid filter is sent normalized`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), typed, 0, ExpressionField("love night", 0)) as RhymeQueryPlan.Query
        assertEquals("love & night", plan.request.tagFilter)
    }

    @Test
    fun `an invalid filter blocks the request`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), typed, 0, ExpressionField("love &", 0))
        assertEquals(TagExprErrorCode.UNEXPECTED_END, (plan as RhymeQueryPlan.Blocked).error.code)
    }

    @Test
    fun `the filter is ignored when the notes source is off`() {
        val params = RhymeQueryParams(sources = RhymeSources(notes = false, dictionary = true))
        val plan = planRhymeQuery(c1, params, typed, 0, ExpressionField("love &", 0)) as RhymeQueryPlan.Query
        assertNull(plan.request.tagFilter)
    }

    @Test
    fun `nothing to ask still skips`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), RhymeSelection(emptySet(), emptyMap()), 0, ExpressionField("love &", 0))
        assertEquals(RhymeQueryPlan.Skip(emptySet(), emptyMap()), plan)
    }
}
