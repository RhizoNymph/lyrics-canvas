package dev.nymph.lyrics.app.canvas

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.canvas.placements.PlacementCanvasPort
import dev.nymph.lyrics.app.canvas.search.CANVAS_SEARCH_DEBOUNCE_MS
import dev.nymph.lyrics.app.canvas.search.CanvasSearchMemory
import dev.nymph.lyrics.app.canvas.search.FOCUS_SNIPPET_MISSING
import dev.nymph.lyrics.app.canvas.search.FocusPlan
import dev.nymph.lyrics.app.canvas.search.HIDDEN_BY_TAG_FILTER
import dev.nymph.lyrics.app.canvas.search.planFocus
import dev.nymph.lyrics.app.canvas.placements.userMessage
import dev.nymph.lyrics.app.canvas.rename.CanvasRenamer
import dev.nymph.lyrics.app.canvas.rename.RenameDialog
import dev.nymph.lyrics.app.canvaslist.CanvasUpdates
import dev.nymph.lyrics.app.rhyme.RhymeCanvasPort
import dev.nymph.lyrics.app.rhyme.RhymeCanvasView
import dev.nymph.lyrics.app.tags.TagCanvasPort
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.app.wordbank.DraftCreateResult
import dev.nymph.lyrics.app.wordbank.WordBankCanvasPort
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.canvas.MergeError
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.logic.canvas.cardBounds
import dev.nymph.lyrics.logic.canvas.centredOn
import dev.nymph.lyrics.logic.canvas.hitTestScreen
import dev.nymph.lyrics.logic.canvas.toMergeError
import dev.nymph.lyrics.logic.canvas.viewportCenter
import dev.nymph.lyrics.logic.list.ListOrder
import dev.nymph.lyrics.logic.placements.RemovalPlan
import dev.nymph.lyrics.logic.placements.applyTransfer
import dev.nymph.lyrics.logic.placements.toPlacementError
import dev.nymph.lyrics.logic.placements.withCanvasRenamed
import dev.nymph.lyrics.logic.search.SearchQuery
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.matches
import dev.nymph.lyrics.logic.tags.withTagDeleted
import dev.nymph.lyrics.logic.tags.withTagEdited
import dev.nymph.lyrics.logic.tags.withTagLinked
import dev.nymph.lyrics.logic.tags.withTagUnlinked
import dev.nymph.lyrics.model.CanvasDetail
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.CanvasRef
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TransferResponse
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quiet period after the last pan / zoom before the viewport is saved. */
const val VIEWPORT_SAVE_DELAY_MS: Long = 1_000

/** Shown when the open canvas disappears from the local copy (deleted elsewhere). */
const val CANVAS_GONE: String = "This canvas was deleted."

/** [SavedStateHandle] key of the canvas / list mode ([CanvasModeKind.name]). */
const val CANVAS_MODE_KEY: String = "canvas_mode"

/** [SavedStateHandle] key of the snippet to focus on open: the `CanvasRoute.focusSnippetId` nav argument. */
const val FOCUS_SNIPPET_KEY: String = "focusSnippetId"

/** [SavedStateHandle] key set once the focus was applied, so a rotation or reload does not apply it again. */
const val FOCUS_HANDLED_KEY: String = "focus_handled"

/**
 * The canvas screen: loaded snippets, camera, selection mode, card moves, merge / delete, and the
 * canvas side of the rhyme sheet ([RhymeCanvasPort]). All coordinates from the screen are in dp.
 * Never throws: failures become [CanvasUiState.Failed], [CanvasUiState.Ready.mergeError] or a
 * [CanvasEvent.Message].
 *
 * List mode shows the same snippets as rows ([CanvasUiState.Ready.listRows]) in a [ListOrder]
 * captured when the list is shown, reloaded, or an editor save comes back, so rows never move
 * under the user's finger; selection, merge and delete are shared with the canvas.
 *
 * Tags ([TagCanvasPort]): the filter bar's filter hides non-matching snippets from both views
 * ([CanvasUiState.Ready.visibleSnippets]) and deselects them; picker links, renames and deletes
 * update the snippets' embedded tags.
 *
 * Search: [editSearch] filters live (debounced [CANVAS_SEARCH_DEBOUNCE_MS], a clear at once) on the
 * loaded snippets' display text, ANDed with the tag filter in [CanvasUiState.Ready.visibleSnippets];
 * the text lasts per canvas for the app session ([CanvasSearchMemory]). A [FOCUS_SNIPPET_KEY] nav
 * argument opens the canvas focused on that snippet (see [planFocus]).
 *
 * Placements: every layout save names this canvas; plain Delete removes the selection from this
 * canvas only ([deleteSelected], deleted for good where it was the last canvas), [deleteEverywhereSelected]
 * deletes it from every canvas; [PlacementCanvasPort] applies transfers from the picker; the title is
 * renamed through [rename]. There is no undo on Android, so nothing needs clearing after these.
 *
 * @param persistScope outlives this ViewModel, so a viewport save started on leave completes
 * @param savedState keeps the canvas / list mode across configuration changes and process death
 * @param canvasUpdates tells the canvas list about a rename
 * @param searchMemory the in-canvas search text per canvas for the session
 */
@OptIn(FlowPreview::class)
class CanvasViewModel(
    private val canvasId: CanvasId,
    private val canvases: CanvasRepository,
    private val snippets: SnippetRepository,
    private val persistScope: CoroutineScope,
    private val viewportSaveDelayMs: Long = VIEWPORT_SAVE_DELAY_MS,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    canvasUpdates: CanvasUpdates = CanvasUpdates(),
    private val searchMemory: CanvasSearchMemory = CanvasSearchMemory(),
    private val searchDebounceMs: Long = CANVAS_SEARCH_DEBOUNCE_MS,
) : ViewModel(), RhymeCanvasPort, TagCanvasPort, WordBankCanvasPort, PlacementCanvasPort {
    private val _state = MutableStateFlow<CanvasUiState>(CanvasUiState.Loading)
    val state: StateFlow<CanvasUiState> = _state.asStateFlow()

    private val _events = Channel<CanvasEvent>(Channel.BUFFERED)
    val events: Flow<CanvasEvent> = _events.receiveAsFlow()

    /** Where each card being moved started, to revert a failed commit. */
    private val moveOrigins = mutableMapOf<SnippetId, Point>()

    private var lastSavedViewport: Viewport? = null

    /** The filter in force, also before the canvas loads (the saved filter may arrive first). */
    private var tagFilter: TagFilter = TagFilter.Everything
    private var viewportSaveJob: Job? = null

    private val _searchInput = MutableStateFlow(searchMemory.get(canvasId))

    /** The search field's text (applied after [searchDebounceMs]). */
    val searchInput: StateFlow<String> = _searchInput.asStateFlow()

    /** The applied search, also before the canvas loads. */
    private var search: SearchQuery = SearchQuery.parse(_searchInput.value)

    /** The snippet just focused from search, until the tag filter's first say on it (it may be restored later). */
    private var watchedFocus: SnippetId? = null

    /** A focused snippet to centre once the canvas has a size. */
    private var pendingCentre: SnippetId? = null

    private val renamer = CanvasRenamer(canvases, viewModelScope, canvasUpdates) { canvas ->
        updateReady { it.copy(title = canvas.title, snippets = withCanvasRenamed(it.snippets, canvas.id, canvas.title)) }
    }

    /** The title's rename dialog. */
    val rename: StateFlow<RenameDialog> = renamer.state

    init {
        reload()
        viewModelScope.launch { canvases.observe(canvasId).collect(::followCopy) }
        viewModelScope.launch {
            _searchInput
                .debounce { if (it.isBlank()) 0L else searchDebounceMs }
                .collect { applySearch(SearchQuery.parse(it)) }
        }
    }

    fun reload() {
        _state.value = CanvasUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = canvases.get(canvasId)) {
                is ApiResult.Success -> {
                    val detail = result.value
                    val viewport = Viewport.from(detail.viewportState)
                    lastSavedViewport = viewport
                    CanvasUiState.Ready(
                        canvasId = detail.id,
                        title = detail.title,
                        snippets = detail.snippets,
                        viewport = viewport,
                        viewSize = ViewSize(0.0, 0.0),
                        mode = modeFor(savedModeKind(), detail.snippets),
                        filter = tagFilter,
                        search = search,
                    )
                }
                is ApiResult.Failure -> CanvasUiState.Failed(result.error.userMessage())
            }
            applyFocus()
        }
    }

    /**
     * The local copy changed (an edit here or elsewhere, a sync answer, a snapshot): take its
     * title and snippets, except the position of a card being dragged. The camera stays local.
     * A canvas gone from the copy (deleted on another device) cannot be shown any more.
     */
    private fun followCopy(detail: CanvasDetail?) {
        if (detail == null) {
            if (ready() != null) _state.value = CanvasUiState.Failed(CANVAS_GONE)
            return
        }
        updateReady { ready ->
            val held = ready.byId
            val snippets = detail.snippets.map { s ->
                val dragged = held[s.id]?.takeIf { s.id in moveOrigins }
                if (dragged != null) s.copy(positionX = dragged.positionX, positionY = dragged.positionY) else s
            }
            if (snippets == ready.snippets && detail.title == ready.title) return@updateReady ready
            val ids = snippets.mapTo(HashSet()) { it.id }
            ready.copy(title = detail.title, snippets = snippets, selection = Selection.of(ready.selection.ids.filterTo(LinkedHashSet()) { it in ids })).pruneHidden()
        }
    }

    private inline fun updateReady(transform: (CanvasUiState.Ready) -> CanvasUiState.Ready) {
        _state.update { if (it is CanvasUiState.Ready) transform(it) else it }
    }

    private fun ready(): CanvasUiState.Ready? = _state.value as? CanvasUiState.Ready

    private fun message(text: String) {
        _events.trySend(CanvasEvent.Message(text))
    }

    // ---- Canvas / list mode ----

    private fun savedModeKind(): CanvasModeKind = CanvasModeKind.parse(savedState.get<String>(CANVAS_MODE_KEY))

    private fun modeFor(kind: CanvasModeKind, snippets: List<Snippet>): CanvasMode = when (kind) {
        CanvasModeKind.CANVAS -> CanvasMode.Canvas
        CanvasModeKind.LIST -> CanvasMode.SnippetList(ListOrder.of(snippets))
    }

    /** Show the canvas or the list; showing the list sorts it afresh. Remembered before the canvas loads too. */
    fun setMode(kind: CanvasModeKind) {
        savedState[CANVAS_MODE_KEY] = kind.name
        updateReady { if (it.mode.kind == kind) it else it.copy(mode = modeFor(kind, it.snippets)) }
    }

    /** Capture a new list order from the current snippets (no-op on the canvas). */
    private fun resortList() = updateReady { ready ->
        if (ready.mode is CanvasMode.SnippetList) ready.copy(mode = CanvasMode.SnippetList(ListOrder.of(ready.snippets))) else ready
    }

    fun snippet(id: SnippetId): Snippet? = ready()?.byId?.get(id)

    /** The topmost card under a screen point (dp). */
    fun hitTest(screen: Point): SnippetId? {
        val ready = ready() ?: return null
        return hitTestScreen(ready.visibleSnippets, screen, ready.viewport)?.id
    }

    // ---- Camera ----

    fun setViewSize(size: ViewSize) {
        updateReady { if (it.viewSize == size) it else it.copy(viewSize = size) }
        pendingCentre?.let(::centreOn)
    }

    fun panBy(delta: Point) = setViewport { it.panBy(delta) }

    /** One pinch / two-finger frame: follow the fingers' pan, then zoom about the centroid. */
    fun transform(centroid: Point, pan: Point, zoom: Double) = setViewport { it.panBy(pan).scaleAbout(centroid, zoom) }

    private inline fun setViewport(change: (Viewport) -> Viewport) {
        val ready = ready() ?: return
        val next = change(ready.viewport)
        if (next == ready.viewport) return
        updateReady { it.copy(viewport = next) }
        scheduleViewportSave()
    }

    private fun scheduleViewportSave() {
        viewportSaveJob?.cancel()
        viewportSaveJob = viewModelScope.launch {
            delay(viewportSaveDelayMs)
            saveViewportNow()
        }
    }

    /** Save a pending viewport change at once (the screen calls this when it stops). */
    fun flushViewport() {
        viewportSaveJob?.cancel()
        viewportSaveJob = null
        saveViewportNow()
    }

    private fun saveViewportNow() {
        val ready = ready() ?: return
        val viewport = ready.viewport
        if (viewport == lastSavedViewport) return
        lastSavedViewport = viewport
        persistScope.launch {
            // A failed save is not worth interrupting the user; the next change saves again.
            if (canvases.saveViewport(ready.canvasId, viewport.toState()) is ApiResult.Failure && lastSavedViewport == viewport) {
                lastSavedViewport = null
            }
        }
    }

    // ---- Taps, selection, moves ----

    fun onTap(hit: SnippetId?) {
        val ready = ready() ?: return
        when (val selection = ready.selection) {
            Selection.Idle -> if (hit != null) _events.trySend(CanvasEvent.OpenEditor(hit))
            is Selection.Selecting -> if (hit != null) {
                val next = if (hit in selection.ids) selection.ids - hit else selection.ids + hit
                updateReady { it.copy(selection = Selection.of(next)) }
            }
        }
    }

    /** Long press on a card: select it (entering selection mode) and get ready to move it. */
    fun onLongPress(id: SnippetId) {
        val snippet = snippet(id) ?: return
        moveOrigins[id] = Point(snippet.positionX, snippet.positionY)
        select(id)
    }

    /** Long press on a list row: select it (entering selection mode); rows do not move. */
    fun onListLongPress(id: SnippetId) {
        if (snippet(id) == null) return
        select(id)
    }

    private fun select(id: SnippetId) = updateReady { it.copy(selection = Selection.of(it.selection.ids + id)) }

    fun moveCardBy(id: SnippetId, screenDelta: Point) {
        if (id !in moveOrigins) return
        updateReady { ready ->
            val zoom = ready.viewport.zoom
            ready.copy(
                snippets = ready.snippets.map {
                    if (it.id == id) it.copy(positionX = it.positionX + screenDelta.x / zoom, positionY = it.positionY + screenDelta.y / zoom) else it
                },
            )
        }
    }

    /** Release after a long press: commit the card's position once if it moved. */
    fun endMove(id: SnippetId) {
        val origin = moveOrigins.remove(id) ?: return
        val moved = snippet(id) ?: return
        if (moved.positionX == origin.x && moved.positionY == origin.y) return
        viewModelScope.launch {
            val result = snippets.batchPosition(canvasId, listOf(PositionUpdate(id, moved.positionX, moved.positionY)))
            if (result is ApiResult.Failure) {
                updateReady { ready ->
                    ready.copy(snippets = ready.snippets.map { if (it.id == id) it.copy(positionX = origin.x, positionY = origin.y) else it })
                }
                message(result.error.userMessage())
            }
        }
    }

    fun exitSelection() = updateReady { it.copy(selection = Selection.Idle, mergeError = null) }

    // ---- Selection actions ----

    fun merge(mode: MergeMode) {
        val ready = ready() ?: return
        if (ready.busy) return
        val ids = ready.selection.ids.filter { it in ready.byId }
        val request = MergeRequest.of(ids, mode)
        if (request == null) {
            updateReady { it.copy(mergeError = MergeError.TooFewSnippets) }
            message(MergeError.TooFewSnippets.message)
            return
        }
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = snippets.merge(ready.canvasId, request)) {
                is ApiResult.Success -> {
                    val merged = result.value
                    // The merged snippet replaces every original on this canvas; one also placed
                    // elsewhere keeps those placements (it may be missing from deleted_ids).
                    val deleted = merged.deletedIds.toSet() + request.snippetIds
                    updateReady { current ->
                        current.copy(
                            snippets = current.snippets.filter { it.id !in deleted && it.id != merged.snippet.id } + merged.snippet,
                            selection = Selection.of(setOf(merged.snippet.id)),
                            busy = false,
                            mergeError = null,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    // The server merge is one transaction: a failure changed nothing.
                    val error = toMergeError(result.error)
                    updateReady { it.copy(busy = false, mergeError = error) }
                    message(if (result.error is ApiError.Unauthorized) result.error.userMessage() else error.message)
                }
            }
        }
    }

    /**
     * Plain Delete: remove each selected snippet from this canvas
     * (`DELETE /api/canvases/{cid}/snippets/{sid}`, in parallel). One on other canvases stays there;
     * one only here is deleted for good. Removed ones leave the view, the rest stay selected; the
     * first error goes to the snackbar. The UI confirms first when [RemovalPlan.needsConfirm].
     */
    fun deleteSelected() = removeSelectedWith { id -> snippets.removeFromCanvas(canvasId, id).errorOrNull() }

    /** Delete everywhere (`DELETE /api/snippets/{id}`): every placement and recording goes. The UI always confirms. */
    fun deleteEverywhereSelected() = removeSelectedWith { id -> snippets.deleteEverywhere(id).errorOrNull() }

    private fun removeSelectedWith(call: suspend (SnippetId) -> ApiError?) {
        val ready = ready() ?: return
        if (ready.busy) return
        val ids = ready.selection.ids.filter { it in ready.byId }
        if (ids.isEmpty()) return
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            val results = ids.map { id -> async { id to call(id) } }.awaitAll()
            val removed = results.filter { it.second == null }.map { it.first }.toSet()
            updateReady { current ->
                current.copy(
                    snippets = current.snippets.filter { it.id !in removed },
                    selection = Selection.of(current.selection.ids - removed),
                    busy = false,
                )
            }
            results.firstNotNullOfOrNull { it.second }?.let { message(toPlacementError(it).userMessage()) }
        }
    }

    // ---- Rename ----

    /** Tap on the title: open the rename dialog with the current title. */
    fun startRename() {
        val ready = ready() ?: return
        renamer.open(ready.canvasId, ready.title)
    }

    fun submitRename(text: String) = renamer.submit(text)

    fun dismissRename() = renamer.dismiss()

    // ---- PlacementCanvasPort ----

    override fun onTransferred(response: TransferResponse, target: CanvasRef) = updateReady { ready ->
        val next = applyTransfer(ready.snippets, response, target)
        val left = next.mapTo(HashSet()) { it.id }
        ready.copy(snippets = next, selection = Selection.of(ready.selection.ids.filterTo(LinkedHashSet()) { it in left }))
    }

    // ---- Creating and replacing snippets ----

    /** FAB: create an empty snippet at the viewport centre, then open it in the editor. */
    fun createSnippetAtCenter() {
        val ready = ready() ?: return
        val at = viewportCenter(ready.viewport, ready.viewSize)
        viewModelScope.launch {
            val body = SnippetCreateRequest(positionX = at.x, positionY = at.y, snippetType = SnippetType.FREESTYLE)
            when (val result = snippets.create(ready.canvasId, body)) {
                is ApiResult.Success -> {
                    insert(result.value)
                    _events.trySend(CanvasEvent.OpenEditor(result.value.id))
                }
                is ApiResult.Failure -> message(result.error.userMessage())
            }
        }
    }

    /**
     * A word bank draft: create a snippet with [text] and [type] where the FAB would (the viewport
     * centre), then show it selected (scrolled to in the list) like a new voice take.
     */
    override suspend fun createSnippetFromText(text: String, type: SnippetType): DraftCreateResult {
        val ready = ready() ?: return DraftCreateResult.CanvasNotLoaded
        val at = viewportCenter(ready.viewport, ready.viewSize)
        return when (val result = snippets.create(ready.canvasId, SnippetCreateRequest(positionX = at.x, positionY = at.y, snippetType = type, rawText = text))) {
            is ApiResult.Success -> {
                showCreated(result.value)
                DraftCreateResult.Created(result.value)
            }
            is ApiResult.Failure -> DraftCreateResult.Failed(result.error)
        }
    }

    /** The editor saved [snippet]: replace the old copy in place; back in the list, sort it afresh. */
    fun replaceSnippet(snippet: Snippet) {
        updateReady { ready -> ready.copy(snippets = ready.snippets.map { if (it.id == snippet.id) snippet else it }) }
        resortList()
    }

    // ---- Voice takes ----

    /**
     * Where a voice take recorded on this screen goes: a new snippet at the centre of the viewport
     * in canvas coordinates. List mode uses the same rule with the last canvas viewport (the
     * viewport is kept while the list shows); before anything was laid out that is the viewport's
     * origin (0, 0 for a canvas never panned). Null until the canvas is loaded.
     */
    fun voiceTarget(): VoiceTakeTarget.New? {
        val ready = ready() ?: return null
        val at = viewportCenter(ready.viewport, ready.viewSize)
        return VoiceTakeTarget.New(at.x, at.y)
    }

    /** A voice take was stored: show the new snippet selected (and scrolled to in the list), or the appended one updated. */
    fun onVoiceTakeSaved(response: VoiceTakeResponse) {
        val snippet = response.snippet
        if (!response.created) {
            replaceSnippet(snippet)
            return
        }
        showCreated(snippet)
    }

    private fun showCreated(snippet: Snippet) {
        insert(snippet)
        updateReady { it.copy(selection = Selection.of(setOf(snippet.id))) }
        if (ready()?.mode is CanvasMode.SnippetList) _events.trySend(CanvasEvent.RevealSnippet(snippet.id))
    }

    private fun insert(snippet: Snippet) = updateReady { ready ->
        ready.copy(snippets = ready.snippets.filter { it.id != snippet.id } + snippet)
    }

    // ---- RhymeCanvasPort ----

    private fun CanvasUiState.toRhymeView(): RhymeCanvasView? = (this as? CanvasUiState.Ready)?.let {
        RhymeCanvasView(it.canvasId, it.selection.ids, it.byId, it.viewport, it.viewSize)
    }

    override val rhymeView: Flow<RhymeCanvasView?> = _state.map { it.toRhymeView() }

    override fun currentRhymeView(): RhymeCanvasView? = _state.value.toRhymeView()

    override fun insertMaterialized(snippet: Snippet) = insert(snippet)

    // ---- TagCanvasPort ----

    override fun currentSnippets(): Map<SnippetId, Snippet> = ready()?.byId ?: emptyMap()

    override fun setTagFilter(filter: TagFilter) {
        tagFilter = filter
        updateReady { ready -> if (ready.filter == filter) ready else ready.copy(filter = filter).pruneHidden() }
        val watched = watchedFocus ?: return
        val snippet = ready()?.byId?.get(watched) ?: return
        watchedFocus = null
        if (!filter.matches(snippet)) message(HIDDEN_BY_TAG_FILTER)
    }

    private inline fun mapSnippet(id: SnippetId, change: (Snippet) -> Snippet) = updateReady { ready ->
        ready.copy(snippets = ready.snippets.map { if (it.id == id) change(it) else it })
    }

    override fun onTagLinked(snippet: SnippetId, tag: Tag) = mapSnippet(snippet) { it.withTagLinked(tag) }

    override fun onTagUnlinked(snippet: SnippetId, tag: TagId) = mapSnippet(snippet) { it.withTagUnlinked(tag) }

    override fun onTagEdited(tag: Tag) = updateReady { it.copy(snippets = it.snippets.withTagEdited(tag)) }

    override fun onTagDeleted(tag: TagId) = updateReady { it.copy(snippets = it.snippets.withTagDeleted(tag)) }

    // ---- In-canvas search ----

    /** The search field changed: remembered for the session, applied after the debounce. */
    fun editSearch(text: String) {
        _searchInput.value = text
        searchMemory.set(canvasId, text)
    }

    /** The × (or Escape): clear the search at once. */
    fun clearSearch() {
        editSearch("")
        applySearch(SearchQuery.EVERYTHING)
    }

    private fun applySearch(query: SearchQuery) {
        search = query
        updateReady { ready -> if (ready.search == query) ready else ready.copy(search = query).pruneHidden() }
    }

    /** Hidden snippets leave the selection. */
    private fun CanvasUiState.Ready.pruneHidden(): CanvasUiState.Ready {
        val shown = visibleSnippets.mapTo(HashSet()) { it.id }
        return copy(selection = Selection.of(selection.ids.filterTo(LinkedHashSet()) { it in shown }))
    }

    // ---- Focus on a snippet (opened from search) ----

    private fun applyFocus() {
        val id = savedState.get<String>(FOCUS_SNIPPET_KEY)?.let(::SnippetId) ?: return
        if (savedState.get<Boolean>(FOCUS_HANDLED_KEY) == true) return
        val ready = ready() ?: return
        savedState[FOCUS_HANDLED_KEY] = true
        when (val plan = planFocus(ready.snippets, id, ready.filter, ready.search)) {
            FocusPlan.Missing -> message(FOCUS_SNIPPET_MISSING)
            is FocusPlan.Reveal -> {
                if (plan.clearSearch) clearSearch()
                if (plan.hiddenByTags) {
                    message(HIDDEN_BY_TAG_FILTER)
                } else {
                    updateReady { it.copy(selection = Selection.of(setOf(id))) }
                    watchedFocus = id
                }
                when (ready.mode) {
                    CanvasMode.Canvas -> centreOn(id)
                    is CanvasMode.SnippetList -> if (!plan.hiddenByTags) _events.trySend(CanvasEvent.RevealSnippet(id))
                }
            }
        }
    }

    /** Pan (zoom kept) so the card's centre is the view's centre; waits for the canvas size. */
    private fun centreOn(id: SnippetId) {
        val ready = ready() ?: return
        val snippet = ready.byId[id] ?: return run { pendingCentre = null }
        if (ready.viewSize.width <= 0.0 || ready.viewSize.height <= 0.0) {
            pendingCentre = id
            return
        }
        pendingCentre = null
        val bounds = cardBounds(snippet)
        setViewport { centredOn(it, Point(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2), ready.viewSize) }
    }
}
