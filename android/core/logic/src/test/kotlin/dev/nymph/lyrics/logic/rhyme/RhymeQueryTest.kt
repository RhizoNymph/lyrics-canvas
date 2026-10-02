package dev.nymph.lyrics.logic.rhyme

import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.snippet
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.DerivedFrom
import dev.nymph.lyrics.model.GridCell
import dev.nymph.lyrics.model.GridData
import dev.nymph.lyrics.model.GridLine
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeLimits
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Slot
import dev.nymph.lyrics.model.SnippetAnchorRef
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.TextAnchorRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The request-building rules of frontend/src/stores/rhymeStore.ts (buildRequest, pruning, derived_from). */
class RhymeQueryTest {
    private val c1 = CanvasId("c1")
    private val a = SnippetId("a")
    private val b = SnippetId("b")

    private fun stressSnippet(id: String, rawText: String, stress: String, x: Double = 0.0, y: Double = 0.0) = snippet(
        id,
        rawText,
        GridData(listOf(GridLine(stress.map { GridCell(syllable = "x", stress = it.digitToInt()) }, stress.length, rawText))),
        x,
        y,
    )

    private val ranged = snippet(
        "a",
        "orange door hinge",
        GridData(
            listOf(
                GridLine(
                    listOf(
                        GridCell(syllable = "or", stress = 1, wordIndex = 0),
                        GridCell(syllable = "ange", stress = 0, wordIndex = 0),
                        GridCell(syllable = "door", stress = 1, wordIndex = 1),
                        GridCell(syllable = "hinge", stress = 1, wordIndex = 2),
                    ),
                    4,
                    "orange door hinge",
                ),
            ),
        ),
        100.0,
        100.0,
    )
    private val range = AnchorRange(0, 1, 3)

    private val snippets = mapOf(
        a to stressSnippet("a", "orange door hinge", "1010", 100.0, 100.0),
        b to stressSnippet("b", "four inch", "11", 300.0, 300.0),
    )

    private fun selection(
        selected: Set<SnippetId> = setOf(a),
        typed: String = "",
        excluded: Set<SnippetId> = emptySet(),
        ranges: Map<SnippetId, AnchorRange> = emptyMap(),
        dismissed: Set<String> = emptySet(),
        all: Map<SnippetId, dev.nymph.lyrics.model.Snippet> = snippets,
    ) = RhymeSelection(selected, all, typed, excluded, ranges, dismissed)

    private fun query(plan: RhymeQueryPlan): RhymeSuggestRequest = (plan as RhymeQueryPlan.Query).request

    @Test
    fun `builds the request from selection, typed text, params, and dismissed ids`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(minScore = 0.6), selection(typed = " door hinge ", dismissed = setOf("dict:orange")), 0)
        assertEquals(
            RhymeSuggestRequest(
                canvasId = c1,
                anchors = listOf(SnippetAnchorRef(a), TextAnchorRef("door hinge")),
                context = emptyList(),
                slot = Slot(4, "1010"),
                k = RhymeK.K2,
                minScore = 0.6,
                sources = listOf(SourceName.NOTES, SourceName.DICTIONARY),
                includeRare = false,
                excludeIds = listOf("dict:orange"),
                limit = RhymeLimits.PAGE_SIZE,
                offset = 0,
                weights = null,
                dialect = null,
            ),
            query(plan),
        )
        assertEquals(Slot(4, "1010"), (plan as RhymeQueryPlan.Query).slot)
    }

    @Test
    fun `uses the manual slot when slotMode is manual, and null when unset`() {
        val manual = RhymeQueryParams(slotMode = SlotMode.MANUAL, slot = Slot(2, "10"))
        assertEquals(Slot(2, "10"), query(planRhymeQuery(c1, manual, selection(), 0)).slot)
        assertNull(query(planRhymeQuery(c1, manual.copy(slot = null), selection(), 0)).slot)
    }

    @Test
    fun `sends only enabled sources and skips the query when none are enabled`() {
        val dictOnly = RhymeQueryParams(sources = RhymeSources(notes = false, dictionary = true))
        assertEquals(listOf(SourceName.DICTIONARY), query(planRhymeQuery(c1, dictOnly, selection(), 0)).sources)
        val none = RhymeQueryParams(sources = RhymeSources(notes = false, dictionary = false))
        assertTrue(planRhymeQuery(c1, none, selection(), 0) is RhymeQueryPlan.Skip)
    }

    @Test
    fun `skips the query when there are no anchors`() {
        assertTrue(planRhymeQuery(c1, RhymeQueryParams(), selection(selected = emptySet()), 0) is RhymeQueryPlan.Skip)
    }

    @Test
    fun `sends include_rare, k and the page offset`() {
        val req = query(planRhymeQuery(c1, RhymeQueryParams(includeRare = true, k = RhymeK.K3), selection(), 80))
        assertEquals(true, req.includeRare)
        assertEquals(RhymeK.K3, req.k)
        assertEquals(80, req.offset)
    }

    @Test
    fun `drops the excluded snippet from the anchors and the slot source`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), selection(selected = setOf(a, b), excluded = setOf(a)), 0) as RhymeQueryPlan.Query
        assertEquals(listOf(SnippetAnchorRef(b)), plan.request.anchors)
        assertEquals(Slot(2, "11"), plan.slot)
    }

    @Test
    fun `forgets an exclusion once the snippet leaves the selection`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), selection(selected = setOf(b), excluded = setOf(a)), 0)
        assertEquals(emptySet<SnippetId>(), plan.excluded)
        assertEquals(setOf(a), pruneExclusions(setOf(a, b), setOf(a)))
    }

    @Test
    fun `sends the range as line_index, word_start, word_end and derives the slot from it`() {
        val plan = planRhymeQuery(c1, RhymeQueryParams(), selection(ranges = mapOf(a to range), all = mapOf(a to ranged)), 0) as RhymeQueryPlan.Query
        assertEquals(listOf(SnippetAnchorRef(a, 0, 1, 3)), plan.request.anchors)
        assertEquals(Slot(2, "11"), plan.slot)
        val whole = planRhymeQuery(c1, RhymeQueryParams(), selection(all = mapOf(a to ranged)), 0) as RhymeQueryPlan.Query
        assertEquals(Slot(4, "1011"), whole.slot)
    }

    @Test
    fun `forgets a range once the snippet leaves the selection or no longer fits`() {
        val left = planRhymeQuery(c1, RhymeQueryParams(), selection(selected = setOf(b), ranges = mapOf(a to range), all = mapOf(a to ranged, b to snippets.getValue(b))), 0)
        assertEquals(emptyMap<SnippetId, AnchorRange>(), left.ranges)
        val shrunk = mapOf(a to stressSnippet("a", "hinge", "1", 100.0, 100.0))
        val plan = planRhymeQuery(c1, RhymeQueryParams(), selection(ranges = mapOf(a to range), all = shrunk), 0)
        assertEquals(emptyMap<SnippetId, AnchorRange>(), plan.ranges)
        assertEquals(listOf(SnippetAnchorRef(a)), query(plan).anchors)
    }

    @Test
    fun `an excluded chip keeps its range for when it is restored`() {
        val plan = planRhymeQuery(
            c1,
            RhymeQueryParams(),
            selection(typed = "tin", excluded = setOf(a), ranges = mapOf(a to range), all = mapOf(a to ranged)),
            0,
        )
        assertEquals(listOf(TextAnchorRef("tin")), query(plan).anchors)
        assertEquals(mapOf(a to range), plan.ranges)
    }

    private val anchorUnit = RhymeUnit(
        id = "note:a:0:0", text = "hinge", provenance = Provenance.NOTE, ephemeral = true, syllableCount = 1, stress = "1",
        rhymeKey = listOf("IH", "N", "JH"), syllables = emptyList(), gridLine = GridLine(emptyList()),
    )
    private val tin = RhymeCandidate(
        id = "dict:tin", text = "tin", provenance = Provenance.DICTIONARY, ephemeral = true, syllableCount = 1, stress = "1",
        rhymeKey = listOf("IH", "N"), syllables = emptyList(), gridLine = GridLine(emptyList()), score = 0.9, matchDepth = 1,
        bestAnchorId = "note:a:0:0", fitsSlot = true, alignment = emptyList(), source = SourceName.DICTIONARY,
    )

    @Test
    fun `derived_from records the candidate, k and the best anchor's text`() {
        assertEquals(
            DerivedFrom(Provenance.DICTIONARY, "dict:tin", 0.9, 1, 2, "note:a:0:0", "hinge"),
            derivedFromFor(tin, listOf(anchorUnit), RhymeK.K2),
        )
        assertEquals("", derivedFromFor(tin, emptyList(), RhymeK.K2).anchorText)
    }

    @Test
    fun `materialize request is a freestyle snippet with the candidate text at the given position`() {
        assertEquals(
            SnippetCreateRequest(
                positionX = 340.0,
                positionY = 100.0,
                snippetType = SnippetType.FREESTYLE,
                rawText = "tin",
                derivedFrom = derivedFromFor(tin, listOf(anchorUnit), RhymeK.K2),
            ),
            materializeRequest(tin, listOf(anchorUnit), RhymeK.K2, Point(340.0, 100.0)),
        )
    }
}
