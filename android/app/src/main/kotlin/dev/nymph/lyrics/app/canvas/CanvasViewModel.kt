package dev.nymph.lyrics.app.canvas

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dev.nymph.lyrics.logic.canvas.hitTestScreen
import dev.nymph.lyrics.logic.canvas.toMergeError
import dev.nymph.lyrics.logic.canvas.viewportCenter
import dev.nymph.lyrics.logic.list.ListOrder
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.withTagDeleted
import dev.nymph.lyrics.logic.tags.withTagEdited
import dev.nymph.lyrics.logic.tags.withTagLinked
import dev.nymph.lyrics.logic.tags.withTagUnlinked
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.voice.VoiceTakeResponse
import dev.nymph.lyrics.model.voice.VoiceTakeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quiet period after the last pan / zoom before the viewport is saved. */
const val VIEWPORT_SAVE_DELAY_MS: Long = 1_000

/** [SavedStateHandle] key of the canvas / list mode ([CanvasModeKind.name]). */
const val CANVAS_MODE_KEY: String = "canvas_mode"

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
 * @param persistScope outlives this ViewModel, so a viewport save started on leave completes
 * @param savedState keeps the canvas / list mode across configuration changes and process death
 */
class CanvasViewModel(
    private val canvasId: CanvasId,
    private val canvases: CanvasRepository,
    private val snippets: SnippetRepository,
    private val persistScope: CoroutineScope,
    private val viewportSaveDelayMs: Long = VIEWPORT_SAVE_DELAY_MS,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel(), RhymeCanvasPort, TagCanvasPort, WordBankCanvasPort {
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

    init {
        reload()
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
                    )
                }
                is ApiResult.Failure -> CanvasUiState.Failed(result.error.userMessage())
            }
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

    fun setViewSize(size: ViewSize) = updateReady { if (it.viewSize == size) it else it.copy(viewSize = size) }

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
            val result = snippets.batchPosition(listOf(PositionUpdate(id, moved.positionX, moved.positionY)))
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
                    val deleted = merged.deletedIds.toSet()
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

    fun deleteSelected() {
        val ready = ready() ?: return
        if (ready.busy) return
        val ids = ready.selection.ids.filter { it in ready.byId }
        if (ids.isEmpty()) return
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            val results = ids.map { id -> async { id to snippets.delete(id) } }.awaitAll()
            val deleted = results.filter { it.second is ApiResult.Success }.map { it.first }.toSet()
            updateReady { current ->
                current.copy(
                    snippets = current.snippets.filter { it.id !in deleted },
                    selection = Selection.of(current.selection.ids - deleted),
                    busy = false,
                )
            }
            results.firstNotNullOfOrNull { it.second.errorOrNull() }?.let { message(it.userMessage()) }
        }
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
        updateReady { ready ->
            if (ready.filter == filter) return@updateReady ready
            val next = ready.copy(filter = filter)
            val shown = next.visibleSnippets.mapTo(HashSet()) { it.id }
            next.copy(selection = Selection.of(ready.selection.ids.filterTo(LinkedHashSet()) { it in shown }))
        }
    }

    private inline fun mapSnippet(id: SnippetId, change: (Snippet) -> Snippet) = updateReady { ready ->
        ready.copy(snippets = ready.snippets.map { if (it.id == id) change(it) else it })
    }

    override fun onTagLinked(snippet: SnippetId, tag: Tag) = mapSnippet(snippet) { it.withTagLinked(tag) }

    override fun onTagUnlinked(snippet: SnippetId, tag: TagId) = mapSnippet(snippet) { it.withTagUnlinked(tag) }

    override fun onTagEdited(tag: Tag) = updateReady { it.copy(snippets = it.snippets.withTagEdited(tag)) }

    override fun onTagDeleted(tag: TagId) = updateReady { it.copy(snippets = it.snippets.withTagDeleted(tag)) }
}
