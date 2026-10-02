package dev.nymph.lyrics.app.rhyme

import dev.nymph.lyrics.logic.rhyme.ManualSlotError
import dev.nymph.lyrics.logic.rhyme.ManualSlotResult
import dev.nymph.lyrics.logic.rhyme.RhymeError
import dev.nymph.lyrics.logic.rhyme.RhymeQueryParams
import dev.nymph.lyrics.logic.rhyme.ShownRange
import dev.nymph.lyrics.logic.rhyme.SlotMode
import dev.nymph.lyrics.logic.rhyme.listRows
import dev.nymph.lyrics.logic.rhyme.parseManualSlot
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.Provenance
import dev.nymph.lyrics.model.RhymeCandidate
import dev.nymph.lyrics.model.RhymeUnit
import dev.nymph.lyrics.model.Slot
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.logic.rhyme.canLoadMore as canLoadMoreRows
import dev.nymph.lyrics.logic.rhyme.shownRange as shownRows

/** The inputs a rhyme query depends on (besides the canvas selection). */
data class RhymeControls(
    val typedText: String,
    val params: RhymeQueryParams,
    val excluded: Set<SnippetId>,
    val ranges: Map<SnippetId, AnchorRange>,
)

/** The rhyme sheet session (rhymeStore.ts). Results never reach the canvas until materialized. */
data class RhymeUiState(
    val open: Boolean = false,
    val typedText: String = "",
    val params: RhymeQueryParams = RhymeQueryParams(),
    /** Raw manual-slot inputs; [params] holds the parsed slot when valid. */
    val manualCount: String = "",
    val manualStress: String = "",
    /** Chips removed from this query. */
    val excluded: Set<SnippetId> = emptySet(),
    /** Word ranges picked on chips. */
    val ranges: Map<SnippetId, AnchorRange> = emptyMap(),
    /** Chips whose next word tap closes the range. */
    val awaitingEnd: Set<SnippetId> = emptySet(),
    val expandedChips: Set<SnippetId> = emptySet(),
    /** Anchor units echoed by the server. */
    val anchors: List<RhymeUnit> = emptyList(),
    val candidates: List<RhymeCandidate> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    /** Dismissed or materialized candidate ids; sent as `exclude_ids`. */
    val dismissed: Set<String> = emptySet(),
    /** Materializations per anchor snippet this session (row step-down). */
    val materializeCounts: Map<SnippetId, Int> = emptyMap(),
    /** The slot sent with the last query. */
    val slot: Slot? = null,
    val loading: Boolean = false,
    val error: RhymeError? = null,
    val expandedRow: String? = null,
    /** Candidates whose materialize request is in flight. */
    val materializing: Set<String> = emptySet(),
    /** The canvas as last seen: selection and snippets are current, the camera may be stale (read it from the port). */
    val canvas: RhymeCanvasView? = null,
) {
    val controls: RhymeControls get() = RhymeControls(typedText, params, excluded, ranges)

    val chips: List<AnchorChip> get() = anchorChips(canvas, excluded, ranges, anchors, expandedChips, awaitingEnd)

    val rows: List<RhymeCandidate> get() = listRows(candidates, offset, dismissed)

    val shown: ShownRange get() = shownRows(rows.size, total)

    val canLoadMore: Boolean get() = canLoadMoreRows(loading, offset, total)

    /** The typed phrase's echoed rhyme key. */
    val typedKey: String? get() = anchors.firstOrNull { it.provenance == Provenance.GENERATED }?.rhymeKey?.joinToString(" ")

    /** Why the manual slot inputs are invalid; null when valid, empty, or not in manual mode. */
    val manualError: ManualSlotError?
        get() {
            if (params.slotMode != SlotMode.MANUAL || (manualCount.isEmpty() && manualStress.isEmpty())) return null
            return (parseManualSlot(manualCount, manualStress) as? ManualSlotResult.Invalid)?.reason
        }

    /** The parsed manual slot, or null when blank or invalid. */
    fun manualSlot(): Slot? = if (manualCount.isEmpty() && manualStress.isEmpty()) {
        null
    } else {
        (parseManualSlot(manualCount, manualStress) as? ManualSlotResult.Ok)?.slot
    }
}
