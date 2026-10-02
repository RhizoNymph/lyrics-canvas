package dev.nymph.lyrics.app.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.rhyme.RhymeCanvasPort
import dev.nymph.lyrics.app.rhyme.RhymeCanvasView
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.CanvasRepository
import dev.nymph.lyrics.data.repository.SnippetRepository
import dev.nymph.lyrics.logic.canvas.MergeError
import dev.nymph.lyrics.logic.canvas.Point
import dev.nymph.lyrics.logic.canvas.ViewSize
import dev.nymph.lyrics.logic.canvas.Viewport
import dev.nymph.lyrics.logic.canvas.hitTestScreen
import dev.nymph.lyrics.logic.canvas.toMergeError
import dev.nymph.lyrics.logic.canvas.viewportCenter
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.MergeMode
import dev.nymph.lyrics.model.MergeRequest
import dev.nymph.lyrics.model.PositionUpdate
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetCreateRequest
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
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

/**
 * The canvas screen: loaded snippets, camera, selection mode, card moves, merge / delete, and the
 * canvas side of the rhyme sheet ([RhymeCanvasPort]). All coordinates from the screen are in dp.
 * Never throws: failures become [CanvasUiState.Failed], [CanvasUiState.Ready.mergeError] or a
 * [CanvasEvent.Message].
 *
 * @param persistScope outlives this ViewModel, so a viewport save started on leave completes
 */
class CanvasViewModel(
    private val canvasId: CanvasId,
    private val canvases: CanvasRepository,
    private val snippets: SnippetRepository,
    private val persistScope: CoroutineScope,
    private val viewportSaveDelayMs: Long = VIEWPORT_SAVE_DELAY_MS,
) : ViewModel(), RhymeCanvasPort {
    private val _state = MutableStateFlow<CanvasUiState>(CanvasUiState.Loading)
    val state: StateFlow<CanvasUiState> = _state.asStateFlow()

    private val _events = Channel<CanvasEvent>(Channel.BUFFERED)
    val events: Flow<CanvasEvent> = _events.receiveAsFlow()

    /** Where each card being moved started, to revert a failed commit. */
    private val moveOrigins = mutableMapOf<SnippetId, Point>()

    private var lastSavedViewport: Viewport? = null
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
                    CanvasUiState.Ready(detail.id, detail.title, detail.snippets, viewport, ViewSize(0.0, 0.0))
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

    fun snippet(id: SnippetId): Snippet? = ready()?.byId?.get(id)

    /** The topmost card under a screen point (dp). */
    fun hitTest(screen: Point): SnippetId? {
        val ready = ready() ?: return null
        return hitTestScreen(ready.snippets, screen, ready.viewport)?.id
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
        updateReady { it.copy(selection = Selection.of(it.selection.ids + id)) }
    }

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

    /** The editor saved [snippet]: replace the old copy in place. */
    fun replaceSnippet(snippet: Snippet) = updateReady { ready ->
        ready.copy(snippets = ready.snippets.map { if (it.id == snippet.id) snippet else it })
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
}
