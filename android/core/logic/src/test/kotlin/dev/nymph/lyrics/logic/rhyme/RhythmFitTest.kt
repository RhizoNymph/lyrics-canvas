package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.RhymeBand
import dev.nymph.lyrics.model.RhymeOrder
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhythmUnavailableReason
import dev.nymph.lyrics.model.SnippetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Fit rhythm" on the rhyme sheet: request field, eligibility, outcome, banding, percent. */
class RhythmFitTest {
    private val a = snippet("a", rawText = "orange door hinge")
    private val selection = RhymeSelection(setOf(SnippetId("a")), mapOf(SnippetId("a") to a))
    private val typedOnly = RhymeSelection(emptySet(), emptyMap(), typedText = "door hinge")

    private fun query(params: RhymeQueryParams, sel: RhymeSelection = selection, offset: Int = 0): RhymeSuggestRequest =
        (planRhymeQuery(CanvasId("c1"), params, sel, offset) as RhymeQueryPlan.Query).request

    @Test
    fun `off by default and omitted`() {
        assertFalse(RhymeQueryParams().rhythmFit)
        assertNull(query(RhymeQueryParams()).rhythmFit)
    }

    @Test
    fun `on with a snippet anchor sends rhythm_fit on every page and both orders`() {
        val on = RhymeQueryParams(rhythmFit = true)
        assertEquals(true, query(on).rhythmFit)
        assertEquals(true, query(on, offset = 40).rhythmFit)
        assertEquals(true, query(on.copy(order = RhymeOrder.MEANING)).rhythmFit)
        assertEquals(RhymeOrder.MEANING, query(on.copy(order = RhymeOrder.MEANING)).order)
    }

    @Test
    fun `rhythm fit is the only difference between the two queries`() {
        assertEquals(query(RhymeQueryParams()).copy(rhythmFit = true), query(RhymeQueryParams(rhythmFit = true)))
    }

    @Test
    fun `a typed-text-only query never sends rhythm_fit`() {
        assertNull(query(RhymeQueryParams(rhythmFit = true), typedOnly).rhythmFit)
        assertEquals(query(RhymeQueryParams(), typedOnly), query(RhymeQueryParams(rhythmFit = true), typedOnly))
    }

    @Test
    fun `an excluded snippet chip leaves no snippet anchor`() {
        val excluded = selection.copy(typedText = "door", excluded = setOf(SnippetId("a")))
        assertNull(query(RhymeQueryParams(rhythmFit = true), excluded).rhythmFit)
        assertFalse(rhythmFitEligible(excluded.selectedIds, excluded.snippets, excluded.excluded))
        assertTrue(rhythmFitEligible(selection.selectedIds, selection.snippets, selection.excluded))
        assertFalse(rhythmFitEligible(emptySet(), emptyMap(), emptySet()))
    }

    @Test
    fun `outcome from the response`() {
        assertEquals(RhythmOutcome.NotAsked, rhythmOutcome(asked = false, applied = false, reason = null))
        assertEquals(RhythmOutcome.Applied, rhythmOutcome(asked = true, applied = true, reason = null))
        assertEquals(
            RhythmOutcome.Unavailable(RhythmUnavailableReason.NO_SCORE),
            rhythmOutcome(asked = true, applied = false, reason = RhythmUnavailableReason.NO_SCORE),
        )
        // An old server ignores the field: asked, not applied, no reason.
        assertEquals(RhythmOutcome.Unavailable(RhythmUnavailableReason.OTHER), rhythmOutcome(asked = true, applied = false, reason = null))
        // Not asked: whatever the server says, nothing is shown.
        assertEquals(RhythmOutcome.NotAsked, rhythmOutcome(asked = false, applied = true, reason = null))
    }

    @Test
    fun `unavailable notes name the reason`() {
        assertEquals("Fit rhythm unavailable: this snippet has no score", rhythmNote(RhythmOutcome.Unavailable(RhythmUnavailableReason.NO_SCORE)))
        assertEquals(
            "Fit rhythm unavailable: place every slot syllable on the staff",
            rhythmNote(RhythmOutcome.Unavailable(RhythmUnavailableReason.SLOT_UNPLACED)),
        )
        assertEquals("Fit rhythm unavailable on this server", rhythmNote(RhythmOutcome.Unavailable(RhythmUnavailableReason.OTHER)))
        assertNull(rhythmNote(RhythmOutcome.Applied))
        assertNull(rhythmNote(RhythmOutcome.NotAsked))
    }

    @Test
    fun `rhythm applied bands the list in rhyme order`() {
        val ordering = ResultOrdering(RhymeOrder.RHYME, RhymeOrder.RHYME, RhythmOutcome.Applied)
        assertEquals(OrderPresentation.BANDED, ordering.presentation)
        assertFalse(ordering.meaningUnavailable)
        val rows = listOf(0.97 to RhymeBand.PERFECT, 0.8 to RhymeBand.NEAR)
        val items = withBandHeaders(rows, ordering.presentation) { it.second }
        assertEquals(4, items.size)
    }

    @Test
    fun `meaning unavailable with rhythm applied keeps the bands and the note`() {
        val ordering = ResultOrdering(RhymeOrder.MEANING, RhymeOrder.RHYME, RhythmOutcome.Applied)
        assertEquals(OrderPresentation.BANDED, ordering.presentation)
        assertTrue(ordering.meaningUnavailable)
    }

    @Test
    fun `without rhythm the ordering presentation is unchanged`() {
        assertEquals(OrderPresentation.PLAIN, ResultOrdering().presentation)
        assertEquals(OrderPresentation.MEANING_UNAVAILABLE, ResultOrdering(RhymeOrder.MEANING, RhymeOrder.RHYME).presentation)
        assertTrue(ResultOrdering(RhymeOrder.MEANING, RhymeOrder.RHYME).meaningUnavailable)
        assertEquals(OrderPresentation.BANDED, ResultOrdering(RhymeOrder.MEANING, RhymeOrder.MEANING).presentation)
        assertEquals(OrderPresentation.PLAIN, ResultOrdering(rhythm = RhythmOutcome.Unavailable(RhythmUnavailableReason.NO_SCORE)).presentation)
    }

    @Test
    fun `rhythm percent rounds and clamps`() {
        assertEquals(75, rhythmPercent(0.75))
        assertEquals(67, rhythmPercent(0.666))
        assertEquals(100, rhythmPercent(1.2))
        assertEquals(0, rhythmPercent(-0.1))
        assertNull(rhythmPercent(null))
        assertNull(rhythmPercent(Double.NaN))
    }
}
