package dev.nymph.lyrics.app.rhyme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.NlpRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.rhyme.RangeClickState
import dev.nymph.lyrics.logic.rhyme.RhymeOperation
import dev.nymph.lyrics.logic.rhyme.RhymeQueryPlan
import dev.nymph.lyrics.logic.rhyme.RhymeSelection
import dev.nymph.lyrics.logic.rhyme.SlotMode
import dev.nymph.lyrics.logic.rhyme.anchorSnippets
import dev.nymph.lyrics.logic.rhyme.clickWord
import dev.nymph.lyrics.logic.rhyme.materializePosition
import dev.nymph.lyrics.logic.rhyme.materializeRequest
import dev.nymph.lyrics.logic.rhyme.planRhymeQuery
import dev.nymph.lyrics.logic.rhyme.toAnchorRect
import dev.nymph.lyrics.logic.rhyme.toRhymeError
import dev.nymph.lyrics.model.AnchorRange
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.RhymeK
import dev.nymph.lyrics.model.RhymeLimits
import dev.nymph.lyrics.model.RhymeSuggestRequest
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SourceName
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch

/** Quiet period after a control or selection change before re-querying (useRhymeQuery.ts). */
const val RHYME_QUERY_DEBOUNCE_MS: Long = 150

/** Everything a query plan is computed from. */
private data class PlanInput(
    val controls: RhymeControls,
    val canvasId: CanvasId,
    val selectedIds: Set<SnippetId>,
    val selected: Map<SnippetId, Snippet>,
)

private sealed interface RhymeOp {
    /** A plan from the debounced control / selection stream; skipped when it repeats the last query. */
    data class Run(val plan: RhymeQueryPlan) : RhymeOp

    /** Query now with the current state (the search box's IME action), even if unchanged. */
    data object Refresh : RhymeOp

    data object LoadMore : RhymeOp
}

/** Identity of a query for de-duplication: the request without `exclude_ids` (dismissals do not re-query). */
private fun RhymeQueryPlan.queryKey(): RhymeSuggestRequest? = (this as? RhymeQueryPlan.Query)?.request?.copy(excludeIds = emptyList())

/** Plans that would send the same query and store the same pruning are the same plan. */
private fun RhymeQueryPlan.identity(): Any = listOf(this::class, queryKey(), excluded, ranges)

/**
 * The rhyme sheet (rhymeStore.ts + useRhymeQuery.ts). While open, control and selection changes are
 * planned with [planRhymeQuery], debounced ([RHYME_QUERY_DEBOUNCE_MS]; the first plan runs at once)
 * and executed with `mapLatest`, so a newer query cancels an older one. Never throws: failures
 * become [RhymeUiState.error].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RhymeViewModel(
    private val port: RhymeCanvasPort,
    private val nlp: NlpRepository,
    private val snippets: SnippetRepository,
    private val debounceMs: Long = RHYME_QUERY_DEBOUNCE_MS,
) : ViewModel() {
    private val session = MutableStateFlow(RhymeUiState(canvas = port.currentRhymeView()))
    val state: StateFlow<RhymeUiState> = session.asStateFlow()

    private val manualOps = MutableSharedFlow<RhymeOp>(extraBufferCapacity = 8)
    private var sessionJob: Job? = null
    private var lastQueryKey: RhymeSuggestRequest? = null

    init {
        viewModelScope.launch {
            // Camera-only changes (every pan frame) are not mirrored; materialize reads the camera when it runs.
            port.rhymeView
                .distinctUntilChanged { a, b -> a?.withoutCamera() == b?.withoutCamera() }
                .collect { view -> session.update { it.copy(canvas = view) } }
        }
    }

    // ---- Session ----

    fun open() {
        if (session.value.open) return
        session.update { it.copy(open = true, error = null) }
        sessionJob = viewModelScope.launch { runQueries() }
    }

    /** Ends the session: results, exclusions, ranges and dismissals are dropped; query controls are kept. */
    fun close() {
        sessionJob?.cancel()
        sessionJob = null
        lastQueryKey = null
        session.update {
            RhymeUiState(params = it.params, manualCount = it.manualCount, manualStress = it.manualStress, canvas = it.canvas)
        }
    }

    private suspend fun runQueries() {
        val plans = session
            .map { s -> s.canvas?.let { PlanInput(s.controls, it.canvasId, it.selectedIds, it.selectedSnippets) } }
            .distinctUntilChanged()
            .map { input -> input?.let { plan(it, offset = 0) } }
            .distinctUntilChanged { a, b -> a?.identity() == b?.identity() }
            .withIndex()
            .debounce { if (it.index == 0) 0L else debounceMs }
            .map { indexed -> indexed.value?.let { RhymeOp.Run(it) } }
        merge(plans, manualOps)
            .mapLatest { op -> if (op != null) execute(op) }
            .collect()
    }

    private fun plan(input: PlanInput, offset: Int): RhymeQueryPlan {
        val c = input.controls
        val selection = RhymeSelection(input.selectedIds, input.selected, c.typedText, c.excluded, c.ranges, session.value.dismissed)
        return planRhymeQuery(input.canvasId, c.params, selection, offset)
    }

    private fun currentPlan(offset: Int): RhymeQueryPlan? {
        val s = session.value
        val view = s.canvas ?: return null
        return plan(PlanInput(s.controls, view.canvasId, view.selectedIds, view.selectedSnippets), offset)
    }

    private suspend fun execute(op: RhymeOp) {
        when (op) {
            // The same query as the last one only needs its pruning stored.
            is RhymeOp.Run -> if (op.plan.queryKey() != null && op.plan.queryKey() == lastQueryKey) {
                session.update { it.pruned(op.plan) }
            } else {
                runPlan(op.plan)
            }
            RhymeOp.Refresh -> currentPlan(0)?.let { runPlan(it) }
            RhymeOp.LoadMore -> loadMorePage()
        }
    }

    /** Store the plan's pruned exclusions and ranges (the web store prunes on every query). */
    private fun RhymeUiState.pruned(plan: RhymeQueryPlan): RhymeUiState =
        if (excluded == plan.excluded && ranges == plan.ranges) {
            this
        } else {
            copy(excluded = plan.excluded, ranges = plan.ranges, awaitingEnd = awaitingEnd.filterTo(LinkedHashSet()) { it in plan.ranges })
        }

    private suspend fun runPlan(plan: RhymeQueryPlan) {
        when (plan) {
            is RhymeQueryPlan.Skip -> {
                lastQueryKey = null
                session.update {
                    it.pruned(plan).copy(anchors = emptyList(), candidates = emptyList(), total = 0, offset = 0, loading = false, error = null)
                }
            }
            is RhymeQueryPlan.Query -> {
                val request = plan.request.copy(excludeIds = session.value.dismissed.toList())
                session.update { it.pruned(plan).copy(loading = true, error = null, slot = plan.slot) }
                when (val result = nlp.rhymeSuggest(request)) {
                    is ApiResult.Success -> session.update { s ->
                        s.copy(
                            anchors = result.value.anchors,
                            candidates = result.value.candidates.filter { it.id !in s.dismissed },
                            total = result.value.total,
                            offset = 0,
                            loading = false,
                            error = null,
                            expandedRow = null,
                        )
                    }
                    is ApiResult.Failure -> session.update { it.copy(loading = false, error = toRhymeError(result.error, RhymeOperation.QUERY)) }
                }
                lastQueryKey = plan.queryKey()
            }
        }
    }

    private suspend fun loadMorePage() {
        val s = session.value
        if (!s.canLoadMore) return
        val next = s.offset + RhymeLimits.PAGE_SIZE
        val plan = currentPlan(next) as? RhymeQueryPlan.Query ?: return
        session.update { it.copy(loading = true, error = null) }
        when (val result = nlp.rhymeSuggest(plan.request)) {
            is ApiResult.Success -> session.update { st ->
                val seen = st.candidates.mapTo(HashSet()) { it.id }
                val fresh = result.value.candidates.filter { it.id !in st.dismissed && it.id !in seen }
                st.copy(candidates = st.candidates + fresh, total = result.value.total, offset = next, loading = false, error = null)
            }
            is ApiResult.Failure -> session.update { it.copy(loading = false, error = toRhymeError(result.error, RhymeOperation.QUERY)) }
        }
    }

    fun searchNow() {
        manualOps.tryEmit(RhymeOp.Refresh)
    }

    fun loadMore() {
        if (session.value.canLoadMore) manualOps.tryEmit(RhymeOp.LoadMore)
    }

    // ---- Controls ----

    fun setTypedText(text: String) = session.update { it.copy(typedText = text) }

    fun excludeAnchor(id: SnippetId) = session.update { it.copy(excluded = it.excluded + id) }

    fun restoreAnchor(id: SnippetId) = session.update { it.copy(excluded = it.excluded - id) }

    fun toggleChipExpanded(id: SnippetId) = session.update {
        it.copy(expandedChips = if (id in it.expandedChips) it.expandedChips - id else it.expandedChips + id)
    }

    /** A word tapped on an expanded chip's line: the start / end / clear transitions of [clickWord]. */
    fun onWordTap(id: SnippetId, lineIndex: Int, wordIndex: Int) = session.update { s ->
        val range = s.ranges[id]
        val current = when {
            range == null -> RangeClickState.None
            id in s.awaitingEnd -> RangeClickState.AwaitingEnd(range)
            else -> RangeClickState.Complete(range)
        }
        val next = clickWord(current, lineIndex, wordIndex)
        s.copy(ranges = s.ranges.with(id, next.range), awaitingEnd = if (next.awaitingEnd) s.awaitingEnd + id else s.awaitingEnd - id)
    }

    fun resetRange(id: SnippetId) = session.update { it.copy(ranges = it.ranges - id, awaitingEnd = it.awaitingEnd - id) }

    private fun Map<SnippetId, AnchorRange>.with(id: SnippetId, range: AnchorRange?) = if (range == null) this - id else this + (id to range)

    fun setK(k: RhymeK) = session.update { it.copy(params = it.params.copy(k = k)) }

    fun setMinScore(value: Double) = session.update { it.copy(params = it.params.copy(minScore = value.coerceIn(0.0, 1.0))) }

    fun setSlotMode(mode: SlotMode) = session.update { s ->
        s.copy(params = s.params.copy(slotMode = mode, slot = if (mode == SlotMode.MANUAL) s.manualSlot() else s.params.slot))
    }

    fun setManualSlot(count: String, stress: String) = session.update { s ->
        val next = s.copy(manualCount = count, manualStress = stress)
        if (s.params.slotMode == SlotMode.MANUAL) next.copy(params = next.params.copy(slot = next.manualSlot())) else next
    }

    fun toggleSource(name: SourceName) = session.update { it.copy(params = it.params.copy(sources = it.params.sources.toggle(name))) }

    fun setIncludeRare(value: Boolean) = session.update { it.copy(params = it.params.copy(includeRare = value)) }

    // ---- Rows ----

    fun toggleRow(id: String) = session.update { it.copy(expandedRow = if (it.expandedRow == id) null else id) }

    /** Hide a candidate for this session (also sent as `exclude_ids` from now on). */
    fun dismiss(id: String) = session.update {
        it.copy(
            candidates = it.candidates.filter { c -> c.id != id },
            dismissed = it.dismissed + id,
            expandedRow = if (it.expandedRow == id) null else it.expandedRow,
        )
    }

    /** Create a snippet from the candidate next to its anchor; on success it joins the canvas and the row goes. */
    fun materialize(id: String) {
        val s = session.value
        val view = s.canvas ?: return
        val candidate = s.candidates.firstOrNull { it.id == id } ?: return
        if (id in s.materializing) return
        val anchorSnips = anchorSnippets(view.selectedIds, view.snippets, s.excluded)
        val camera = port.currentRhymeView() ?: view
        val at = materializePosition(anchorSnips.map { it.toAnchorRect() }, s.materializeCounts, camera.viewport, camera.viewSize)
        val body = materializeRequest(candidate, s.anchors, s.params.k, at)
        session.update { it.copy(materializing = it.materializing + id) }
        viewModelScope.launch {
            when (val result = snippets.create(view.canvasId, body)) {
                is ApiResult.Success -> {
                    port.insertMaterialized(result.value)
                    session.update { st ->
                        val counts = if (anchorSnips.size == 1) {
                            val anchorId = anchorSnips[0].id
                            st.materializeCounts + (anchorId to (st.materializeCounts[anchorId] ?: 0) + 1)
                        } else {
                            st.materializeCounts
                        }
                        st.copy(
                            candidates = st.candidates.filter { it.id != id },
                            dismissed = st.dismissed + id,
                            materializeCounts = counts,
                            materializing = st.materializing - id,
                            expandedRow = if (st.expandedRow == id) null else st.expandedRow,
                            error = null,
                        )
                    }
                }
                is ApiResult.Failure -> session.update {
                    it.copy(materializing = it.materializing - id, error = toRhymeError(result.error, RhymeOperation.MATERIALIZE))
                }
            }
        }
    }
}
