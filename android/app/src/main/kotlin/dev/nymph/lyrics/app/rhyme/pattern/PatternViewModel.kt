package dev.nymph.lyrics.app.rhyme.pattern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.rhyme.RHYME_QUERY_DEBOUNCE_MS
import dev.nymph.lyrics.app.rhyme.RhymeCanvasPort
import dev.nymph.lyrics.app.rhyme.truncateChip
import dev.nymph.lyrics.data.repository.RhymePatternRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.rests.displayText
import dev.nymph.lyrics.logic.rhyme.RhymeSources
import dev.nymph.lyrics.logic.rhyme.anchorSnippets
import dev.nymph.lyrics.logic.rhyme.materializePosition
import dev.nymph.lyrics.logic.rhyme.pattern.LineMatchList
import dev.nymph.lyrics.logic.rhyme.pattern.LinePick
import dev.nymph.lyrics.logic.rhyme.pattern.LinePickStep
import dev.nymph.lyrics.logic.rhyme.pattern.PatternFailure
import dev.nymph.lyrics.logic.rhyme.pattern.PatternOperation
import dev.nymph.lyrics.logic.rhyme.pattern.PatternPage
import dev.nymph.lyrics.logic.rhyme.pattern.PatternPlan
import dev.nymph.lyrics.logic.rhyme.pattern.PatternRange
import dev.nymph.lyrics.logic.rhyme.pattern.PatternTarget
import dev.nymph.lyrics.logic.rhyme.pattern.RangeCheck
import dev.nymph.lyrics.logic.rhyme.pattern.SlotBoard
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEdit
import dev.nymph.lyrics.logic.rhyme.pattern.SlotEditResult
import dev.nymph.lyrics.logic.rhyme.pattern.SlotId
import dev.nymph.lyrics.logic.rhyme.pattern.TupleTray
import dev.nymph.lyrics.logic.rhyme.pattern.appendLineMatchPage
import dev.nymph.lyrics.logic.rhyme.pattern.appendSlotPage
import dev.nymph.lyrics.logic.rhyme.pattern.applySlotEdit
import dev.nymph.lyrics.logic.rhyme.pattern.defaultPatternLineCount
import dev.nymph.lyrics.logic.rhyme.pattern.detectRequest
import dev.nymph.lyrics.logic.rhyme.pattern.firstPage
import dev.nymph.lyrics.logic.rhyme.pattern.lastLinesRange
import dev.nymph.lyrics.logic.rhyme.pattern.lineMatchRequest
import dev.nymph.lyrics.logic.rhyme.pattern.makeSnippetRequest
import dev.nymph.lyrics.logic.rhyme.pattern.pickLine
import dev.nymph.lyrics.logic.rhyme.pattern.planPatternSuggest
import dev.nymph.lyrics.logic.rhyme.pattern.toPatternError
import dev.nymph.lyrics.logic.rhyme.pattern.toggleLinePick
import dev.nymph.lyrics.logic.rhyme.toAnchorRect
import dev.nymph.lyrics.logic.tags.Completion
import dev.nymph.lyrics.logic.tags.ExpressionField
import dev.nymph.lyrics.logic.tags.Suggestion
import dev.nymph.lyrics.model.PatternCandidate
import dev.nymph.lyrics.model.PatternLimits
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The single-mode controls a pattern session starts from. */
data class PatternSeed(val minScore: Double, val sources: RhymeSources, val includeRare: Boolean, val tagFilter: ExpressionField)

/** Identity of a suggest query: the request without `exclude_ids` (dismissals do not re-query). */
private fun PatternPlan.key(): Any = when (this) {
    is PatternPlan.Query -> request.copy(excludeIds = emptyList())
    else -> this
}

/**
 * Pattern mode of the rhyme sheet (docs/features/android_rhyme_patterns.md): picks a line range on
 * a chip, detects the slots, keeps the user's slot edits (pure reducer), and re-queries suggest
 * [debounceMs] after any edit or control change, the newest query winning (`mapLatest`). Paging,
 * the tuple tray, "Make snippet" and line-match materialize run on top. Never throws: failures
 * become [PatternSession.failure].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PatternViewModel(
    private val port: RhymeCanvasPort,
    private val patterns: RhymePatternRepository,
    private val snippets: SnippetRepository,
    private val debounceMs: Long = RHYME_QUERY_DEBOUNCE_MS,
) : ViewModel() {
    private val ui = MutableStateFlow(PatternUiState())
    val state: StateFlow<PatternUiState> = ui.asStateFlow()

    private var detectJob: Job? = null
    private val pageJobs = mutableListOf<Job>()

    init {
        viewModelScope.launch { runSuggests() }
    }

    // ---- Entering / leaving ----

    /** The chip's "Lines" toggle. */
    fun toggleLinePick(snippetId: SnippetId) = ui.update { it.copy(linePick = toggleLinePick(it.linePick, snippetId), linePickProblem = null) }

    /** A line number tapped on an expanded chip while picking lines; a valid range enters pattern mode. */
    fun onLineTap(snippetId: SnippetId, lineIndex: Int, seed: PatternSeed) {
        val snippet = port.currentRhymeView()?.snippets?.get(snippetId) ?: return
        when (val step = pickLine(ui.value.linePick, snippet, lineIndex)) {
            is LinePickStep.Next -> ui.update { it.copy(linePick = step.pick, linePickProblem = null) }
            is LinePickStep.Rejected -> ui.update { it.copy(linePick = step.pick, linePickProblem = step.problem) }
            is LinePickStep.Picked -> enter(snippet, step.range, seed)
        }
    }

    /** The "Pattern (last N lines)" stepper; clamped to 2..16. */
    fun setLastLines(count: Int) = ui.update { it.copy(lastLines = count.coerceIn(PatternLimits.MIN_LINES, PatternLimits.MAX_LINES)) }

    /** The stepper's value for [snippet]. */
    fun lastLinesFor(snippet: Snippet): Int = ui.value.lastLines ?: defaultPatternLineCount(snippet)

    /** "Pattern (last N lines)" on the one selected snippet. */
    fun enterLastLines(snippetId: SnippetId, seed: PatternSeed) {
        val snippet = port.currentRhymeView()?.snippets?.get(snippetId) ?: return
        when (val check = lastLinesRange(snippet, lastLinesFor(snippet))) {
            is RangeCheck.Ok -> enter(snippet, check.range, seed)
            is RangeCheck.Invalid -> ui.update { it.copy(linePickProblem = check.problem) }
        }
    }

    private fun enter(snippet: Snippet, range: PatternRange, seed: PatternSeed) {
        val view = port.currentRhymeView() ?: return
        val others = anchorSnippets(view.selectedIds, view.snippets, emptySet()).count { it.id != snippet.id }
        val first = snippet.gridData?.lines?.getOrNull(range.start)?.rawText ?: snippet.rawText
        val session = PatternSession(
            target = PatternTarget(view.canvasId, snippet.id, range),
            label = truncateChip(displayText(first).trim()),
            ignoredSnippets = others,
        )
        cancelJobs()
        ui.update {
            it.copy(
                session = session,
                params = it.params.copy(minScore = seed.minScore, sources = seed.sources, includeRare = seed.includeRare),
                tagFilter = seed.tagFilter,
                linePick = LinePick.Off,
                linePickProblem = null,
            )
        }
        startDetect(delayMs = 0)
    }

    /** "Back to single" and closing the sheet: the session ends; the stepper and threshold are kept. */
    fun exit() {
        cancelJobs()
        ui.update { it.copy(session = null, linePick = LinePick.Off, linePickProblem = null) }
    }

    private fun cancelJobs() {
        detectJob?.cancel()
        detectJob = null
        pageJobs.forEach { it.cancel() }
        pageJobs.clear()
    }

    // ---- Detect ----

    /** "Reset": detect again, discarding edits and picks. */
    fun reset() = startDetect(delayMs = 0)

    /** The detection threshold control; re-detects after the debounce. */
    fun setDetectMinScore(value: Double) {
        val clamped = value.coerceIn(PatternLimits.DETECT_MIN_SCORE_FLOOR, 1.0)
        if (clamped == ui.value.params.detectMinScore) return
        ui.update { it.copy(params = it.params.copy(detectMinScore = clamped)) }
        startDetect(delayMs = debounceMs)
    }

    private fun startDetect(delayMs: Long) {
        val session = ui.value.session ?: return
        detectJob?.cancel()
        pageJobs.forEach { it.cancel() }
        pageJobs.clear()
        val nonce = session.detectNonce + 1
        detectJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            updateSession {
                it.copy(
                    detection = Detection.Running,
                    detectNonce = nonce,
                    slotInfo = emptyMap(),
                    columns = emptyList(),
                    lineMatches = LineMatchList(),
                    tray = TupleTray(),
                    picker = null,
                    notice = null,
                    failure = null,
                    loading = false,
                    pagingSlots = emptySet(),
                    pagingLines = false,
                )
            }
            val request = detectRequest(session.target, ui.value.params)
            val result = patterns.detect(request)
            updateSession { s ->
                if (s.target != session.target || s.detectNonce != nonce) return@updateSession s
                when (result) {
                    is ApiResult.Success -> s.copy(
                        detection = Detection.Ready(result.value.lines, SlotBoard.fromDetect(result.value)),
                        slotInfo = result.value.slots.associateBy { SlotId(it.id) },
                    )
                    is ApiResult.Failure -> s.copy(detection = Detection.Failed, failure = PatternFailure(PatternOperation.DETECT, toPatternError(result.error)))
                }
            }
        }
    }

    // ---- Suggest ----

    private suspend fun runSuggests() {
        ui
            .map { s -> s.plan() }
            .distinctUntilChanged { a, b -> a?.key() == b?.key() }
            .debounce(debounceMs)
            .mapLatest { plan -> if (plan != null) runPlan(plan) }
            .collect()
    }

    /** True when [plan] still describes what the controls would send (a stale answer is dropped). */
    private fun current(plan: PatternPlan): Boolean = ui.value.plan()?.key() == plan.key()

    private suspend fun runPlan(plan: PatternPlan) {
        when (plan) {
            is PatternPlan.Skip, is PatternPlan.Blocked -> updateSession {
                it.copy(columns = emptyList(), lineMatches = LineMatchList(), loading = false)
            }
            is PatternPlan.Query -> {
                pageJobs.forEach { it.cancel() }
                pageJobs.clear()
                updateSession { it.copy(loading = true, failure = null, pagingSlots = emptySet(), pagingLines = false) }
                val request = plan.request.copy(excludeIds = ui.value.session?.dismissed?.sorted().orEmpty())
                val result = patterns.suggest(request)
                if (!current(plan)) return
                updateSession { s ->
                    when (result) {
                        is ApiResult.Success -> {
                            val (columns, lines) = firstPage(result.value, s.dismissed)
                            s.copy(
                                columns = columns,
                                lineMatches = lines,
                                slotInfo = s.slotInfo + result.value.slots.associate { SlotId(it.slot.id) to it.slot },
                                loading = false,
                            )
                        }
                        is ApiResult.Failure -> s.copy(loading = false, failure = PatternFailure(PatternOperation.SUGGEST, toPatternError(result.error)))
                    }
                }
            }
        }
    }

    /** "More" at the bottom of a slot's column. */
    fun loadMoreSlot(slot: SlotId) {
        val s = ui.value.session ?: return
        val column = s.shownColumns.firstOrNull { it.id == slot } ?: return
        if (!column.canLoadMore || slot in s.pagingSlots || s.loading) return
        page(PatternPage.Slot(slot, column.nextOffset))
    }

    /** "More" under the line matches. */
    fun loadMoreLines() {
        val s = ui.value.session ?: return
        if (!s.lineMatches.canLoadMore || s.pagingLines || s.loading) return
        page(PatternPage.LineMatches(s.lineMatches.nextOffset))
    }

    private fun page(page: PatternPage) {
        val st = ui.value
        val s = st.session ?: return
        val board = s.board ?: return
        val pagePlan = planPatternSuggest(s.target, board, st.params, st.tagFilter, s.dismissed, page) as? PatternPlan.Query ?: return
        val basis = st.plan() ?: return
        markPaging(page, true)
        pageJobs += viewModelScope.launch {
            val result = patterns.suggest(pagePlan.request)
            if (!current(basis)) return@launch
            updateSession { cur ->
                val next = when (result) {
                    is ApiResult.Success -> when (page) {
                        is PatternPage.Slot -> cur.copy(columns = appendSlotPage(cur.columns, page.slot, page.offset, result.value, cur.dismissed))
                        is PatternPage.LineMatches -> cur.copy(lineMatches = appendLineMatchPage(cur.lineMatches, page.offset, result.value, cur.dismissed))
                        PatternPage.First -> cur
                    }
                    is ApiResult.Failure -> cur.copy(failure = PatternFailure(PatternOperation.SUGGEST, toPatternError(result.error)))
                }
                next.paging(page, false)
            }
        }
    }

    private fun markPaging(page: PatternPage, on: Boolean) = updateSession { it.paging(page, on) }

    private fun PatternSession.paging(page: PatternPage, on: Boolean): PatternSession = when (page) {
        is PatternPage.Slot -> copy(pagingSlots = if (on) pagingSlots + page.slot else pagingSlots - page.slot)
        is PatternPage.LineMatches -> copy(pagingLines = on)
        PatternPage.First -> this
    }

    // ---- Slot editing ----

    /** A pattern word tapped: open its picker (or close it when it is already open). */
    fun tapWord(lineIndex: Int, word: Int) = updateSession {
        val ref = WordRef(lineIndex, word)
        it.copy(picker = if (it.picker == ref) null else ref, notice = null)
    }

    fun closePicker() = updateSession { it.copy(picker = null) }

    fun edit(edit: SlotEdit) = updateSession { s ->
        val ready = s.detection as? Detection.Ready ?: return@updateSession s
        when (val result = applySlotEdit(ready.board, edit)) {
            is SlotEditResult.Applied -> s.copy(
                detection = ready.copy(board = result.board),
                tray = s.tray.prune(result.board.onSlots.map { it.id }),
                picker = null,
                notice = null,
            )
            is SlotEditResult.Refused -> s.copy(picker = null, notice = refusalText(result.reason, ready.board))
            SlotEditResult.Unchanged -> s.copy(picker = null)
        }
    }

    fun toggleSlot(slot: SlotId) = edit(SlotEdit.Toggle(slot))

    fun dropSlot(slot: SlotId) = edit(SlotEdit.Drop(slot))

    fun setTab(tab: PatternTab) = updateSession { it.copy(tab = tab) }

    // ---- Controls ----

    fun setMinScore(value: Double) = ui.update { it.copy(params = it.params.copy(minScore = value.coerceIn(0.0, 1.0))) }

    fun toggleSource(name: SourceName) = ui.update { it.copy(params = it.params.copy(sources = it.params.sources.toggle(name))) }

    fun setIncludeRare(value: Boolean) = ui.update { it.copy(params = it.params.copy(includeRare = value)) }

    fun editTagFilter(text: String, caret: Int) = ui.update { it.copy(tagFilter = it.tagFilter.edit(text, caret)) }

    fun acceptTagFilterSuggestion(completion: Completion, suggestion: Suggestion) =
        ui.update { it.copy(tagFilter = it.tagFilter.accept(completion, suggestion)) }

    fun insertTagFilterTag(name: String) = ui.update { it.copy(tagFilter = it.tagFilter.insertTag(name)) }

    fun setTagFilterText(text: String) = ui.update { it.copy(tagFilter = it.tagFilter.replaced(text)) }

    fun clearTagFilter() = ui.update { it.copy(tagFilter = it.tagFilter.cleared()) }

    // ---- Rows and the tuple tray ----

    /** Tap a slot row: pick it for the tuple (tap again to unpick). */
    fun togglePick(slot: SlotId, candidate: PatternCandidate) = updateSession { it.copy(tray = it.tray.toggle(slot, candidate.id, candidate.text)) }

    fun clearTray() = updateSession { it.copy(tray = TupleTray()) }

    /** Row ×: hidden for this session in every list and sent as `exclude_ids` from now on. */
    fun dismiss(id: String) = updateSession { s ->
        s.copy(dismissed = s.dismissed + id, columns = s.columns.map { it.without(id) }, lineMatches = s.lineMatches.without(id))
    }

    /** "Make snippet": one line of the picks in slot order, next to the anchor. */
    fun makeSnippet() {
        val s = ui.value.session ?: return
        if (s.creating) return
        val body = makeSnippetRequest(s.tray, s.order, nextPosition(s)) ?: return
        updateSession { it.copy(creating = true) }
        create(body, PatternOperation.MAKE_SNIPPET) { st -> st.copy(creating = false) }
    }

    /** A whole-line match tapped: materialize it next to the anchor, as a note candidate is. */
    fun materializeLine(unitId: String) {
        val s = ui.value.session ?: return
        val match = s.lineMatches.matches.firstOrNull { it.unit.id == unitId } ?: return
        if (unitId in s.materializing) return
        val anchorText = s.lines.joinToString(" / ") { it.text }
        val body = lineMatchRequest(match, s.target, s.slotInfo.values.toList(), anchorText, nextPosition(s))
        updateSession { it.copy(materializing = it.materializing + unitId) }
        create(body, PatternOperation.MATERIALIZE) { st ->
            st.copy(materializing = st.materializing - unitId)
        }
    }

    private fun nextPosition(s: PatternSession): Point {
        val view = port.currentRhymeView()
        val anchor = view?.snippets?.get(s.target.snippetId)
        if (view == null) return Point(0.0, 0.0)
        val anchors = listOfNotNull(anchor?.toAnchorRect())
        return materializePosition(anchors, mapOf(s.target.snippetId to s.made), view.viewport, view.viewSize)
    }

    private fun create(body: SnippetCreateRequest, op: PatternOperation, settle: (PatternSession) -> PatternSession) {
        val s = ui.value.session ?: return
        val target = s.target
        val sourceId = body.derivedFrom?.sourceUnitId
        viewModelScope.launch {
            val result = snippets.create(target.canvasId, body)
            if (result is ApiResult.Success) port.insertMaterialized(result.value)
            updateSession { cur ->
                if (cur.target != target) return@updateSession cur
                val settled = settle(cur)
                when (result) {
                    is ApiResult.Success -> {
                        val done = if (sourceId != null) settled.copy(dismissed = settled.dismissed + sourceId, lineMatches = settled.lineMatches.without(sourceId)) else settled
                        done.copy(made = done.made + 1, failure = null)
                    }
                    is ApiResult.Failure -> settled.copy(failure = PatternFailure(op, toPatternError(result.error)))
                }
            }
        }
    }

    private inline fun updateSession(crossinline f: (PatternSession) -> PatternSession) = ui.update { st ->
        val s = st.session ?: return@update st
        st.copy(session = f(s))
    }
}
