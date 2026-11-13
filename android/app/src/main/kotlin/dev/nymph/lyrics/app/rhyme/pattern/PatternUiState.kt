package dev.nymph.lyrics.app.rhyme.pattern

import dev.nymph.lyrics.logic.rhyme.pattern.LineMatchList
import dev.nymph.lyrics.logic.rhyme.pattern.LinePick
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFailure
import dev.nymph.lyrics.logic.rhyme.pattern.PatternParams
import dev.nymph.lyrics.logic.rhyme.pattern.PatternPlan
import dev.nymph.lyrics.logic.rhyme.pattern.PatternSkip
import dev.nymph.lyrics.logic.rhyme.pattern.PatternTarget
import dev.nymph.lyrics.logic.rhyme.pattern.RangeProblem
import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.SlotColumn
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.rhyme.pattern.TupleTray
import dev.nymph.lyrics.logic.rhyme.pattern.WordAction
import dev.nymph.lyrics.logic.rhyme.pattern.planPatternSuggest
import dev.nymph.lyrics.logic.rhyme.pattern.wordActions
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.RhymeTagFilter
import dev.nymph.lyrics.logic.tags.rhymeTagFilter
import dev.nymph.lyrics.model.PatternLine
import dev.nymph.lyrics.model.PatternSlot

/** Where detection stands; a board exists only once the lines are known. */
sealed interface Detection {
    data object Running : Detection

    /** Detect failed ([PatternSession.failure] says why); Reset retries. */
    data object Failed : Detection

    data class Ready(val lines: List<PatternLine>, val board: SlotBoard) : Detection
}

/** A tapped pattern word, whose picker is open. */
data class WordRef(val lineIndex: Int, val word: Int)

enum class PatternTab { SLOTS, LINES }

/** One pattern-mode session, from entering it to "Back to single" / closing the sheet. */
data class PatternSession(
    val target: PatternTarget,
    /** The snippet's display label (header). */
    val label: String,
    /** Other selected snippets that pattern mode ignores (said in the header). */
    val ignoredSnippets: Int,
    val detection: Detection = Detection.Running,
    /** Bumped by Reset: re-run detect even when nothing else changed. */
    val detectNonce: Int = 0,
    /** The server's latest view of each slot (detect, then every suggest), for keys and stress. */
    val slotInfo: Map<SlotId, PatternSlot> = emptyMap(),
    val columns: List<SlotColumn> = emptyList(),
    val lineMatches: LineMatchList = LineMatchList(),
    val loading: Boolean = false,
    /** Slots whose next page is loading. */
    val pagingSlots: Set<SlotId> = emptySet(),
    val pagingLines: Boolean = false,
    val tray: TupleTray = TupleTray(),
    /** Dismissed or materialized ids this session; sent as `exclude_ids`. */
    val dismissed: Set<String> = emptySet(),
    val failure: PatternFailure? = null,
    /** Why the last slot edit was refused (cleared by the next edit). */
    val notice: String? = null,
    val picker: WordRef? = null,
    val tab: PatternTab = PatternTab.SLOTS,
    val creating: Boolean = false,
    /** Line matches whose materialize request is in flight. */
    val materializing: Set<String> = emptySet(),
    /** Snippets made from this pattern's anchor (placement step-down, like materialize). */
    val made: Int = 0,
) {
    val board: SlotBoard? get() = (detection as? Detection.Ready)?.board

    val lines: List<PatternLine> get() = (detection as? Detection.Ready)?.lines.orEmpty()

    /** The on slots' ids in slot order: the tray and the columns follow it. */
    val order: List<SlotId> get() = board?.onSlots?.map { it.id }.orEmpty()

    /** The open picker's entries. */
    val pickerActions: List<WordAction> get() = picker?.let { p -> board?.let { wordActions(it, p.lineIndex, p.word) } }.orEmpty()

    /** The columns to show: one per on slot, in slot order (a slot not yet answered has none). */
    val shownColumns: List<SlotColumn> get() = order.mapNotNull { id -> columns.firstOrNull { it.id == id } }
}

/** Pattern mode state: the session (null = single mode), line picking and its controls. */
data class PatternUiState(
    val session: PatternSession? = null,
    val params: PatternParams = PatternParams(),
    val tagFilter: ExpressionField = ExpressionField(),
    /** The chips' "Lines" picking. */
    val linePick: LinePick = LinePick.Off,
    /** Why the last line pick was refused. */
    val linePickProblem: RangeProblem? = null,
    /** "Pattern (last N lines)" stepper value; null = the snippet's default. */
    val lastLines: Int? = null,
) {
    val active: Boolean get() = session != null

    val tagFilterBlocked: Boolean get() = rhymeTagFilter(tagFilter, params.sources.notes) is RhymeTagFilter.Blocked

    /** What the current controls would send (null without a board). */
    fun plan(): PatternPlan? {
        val s = session ?: return null
        val board = s.board ?: return null
        return planPatternSuggest(s.target, board, params, tagFilter, s.dismissed)
    }

    /** Why nothing is queried, when that is the case. */
    val skip: PatternSkip? get() = (plan() as? PatternPlan.Skip)?.reason
}
