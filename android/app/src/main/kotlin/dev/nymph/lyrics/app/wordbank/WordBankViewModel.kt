package dev.nymph.lyrics.app.wordbank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.app.tags.TagTarget
import dev.nymph.lyrics.app.ui.userMessage
import dev.nymph.lyrics.data.repository.WordBankRepository
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.TagToggle
import dev.nymph.lyrics.logic.tags.targetsOf
import dev.nymph.lyrics.logic.wordbank.AddPlan
import dev.nymph.lyrics.logic.wordbank.RelatedQueryCheck
import dev.nymph.lyrics.logic.wordbank.WordBankError
import dev.nymph.lyrics.logic.wordbank.WordTextCheck
import dev.nymph.lyrics.logic.wordbank.addSummary
import dev.nymph.lyrics.logic.wordbank.checkRelatedQuery
import dev.nymph.lyrics.logic.wordbank.draftText
import dev.nymph.lyrics.logic.wordbank.mergedWith
import dev.nymph.lyrics.logic.wordbank.planAdd
import dev.nymph.lyrics.logic.wordbank.pruneSelection
import dev.nymph.lyrics.logic.wordbank.relatedRetryDelayMs
import dev.nymph.lyrics.logic.wordbank.rhymesWithAnchor
import dev.nymph.lyrics.logic.wordbank.tagFilterParam
import dev.nymph.lyrics.logic.wordbank.toWordBankError
import dev.nymph.lyrics.logic.wordbank.validateWordText
import dev.nymph.lyrics.logic.wordbank.visibleWords
import dev.nymph.lyrics.logic.wordbank.withTagDeleted
import dev.nymph.lyrics.logic.wordbank.withTagEdited
import dev.nymph.lyrics.logic.wordbank.withoutIds
import dev.nymph.lyrics.logic.wordbank.wordTagLinks
import dev.nymph.lyrics.model.BankFamiliesRequest
import dev.nymph.lyrics.model.BankRhymesRequest
import dev.nymph.lyrics.model.BankWord
import dev.nymph.lyrics.model.BankWordId
import dev.nymph.lyrics.model.SnippetType
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkOp
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.WordBankAddResponse
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Most ids one delete or tag request carries (the server's limit); larger selections are chunked. */
const val MAX_IDS_PER_REQUEST: Int = 500

/**
 * The word bank screen: the whole bank (filtered locally by the shared tag evaluator), the add box,
 * selection, rename / delete, both rhyme views, the related-word search and the snippet draft. It is
 * the [TagTarget] of the screen's tag controls (filter bar, picker). The draft is created on the
 * canvas the screen was opened from, through [canvas]. Never throws: failures become state or
 * [messages].
 */
class WordBankViewModel(
    private val repo: WordBankRepository,
    private val canvas: WordBankCanvasPort,
) : ViewModel(), TagTarget<BankWordId> {
    private val _state = MutableStateFlow(WordBankUiState())
    val state: StateFlow<WordBankUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Snackbar texts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var relatedJob: Job? = null
    private var familiesJob: Job? = null
    private var rhymesJob: Job? = null

    init {
        reload()
        // The local copy changes with every edit and sync; a loaded list follows it.
        viewModelScope.launch { repo.observeWords().collect { list -> updateWords { list } } }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }

    private fun words(): List<BankWord> = _state.value.allWords

    /** Replace the word list (when loaded) and keep the selection within what is shown. */
    private inline fun updateWords(crossinline change: (List<BankWord>) -> List<BankWord>) = _state.update { s ->
        val loaded = s.words as? Remote.Loaded ?: return@update s
        val next = change(loaded.value)
        s.copy(words = Remote.Loaded(next), selection = pruneSelection(s.selection, visibleWords(next, s.filter)))
    }

    fun reload() {
        viewModelScope.launch {
            if (_state.value.words !is Remote.Loaded) _state.update { it.copy(words = Remote.Loading) }
            when (val result = repo.list()) {
                is ApiResult.Success -> _state.update { s ->
                    s.copy(words = Remote.Loaded(result.value), selection = pruneSelection(s.selection, visibleWords(result.value, s.filter)))
                }
                is ApiResult.Failure -> {
                    val error = toWordBankError(result.error)
                    if (_state.value.words is Remote.Loaded) message(error.userMessage()) else _state.update { it.copy(words = Remote.Failed(error)) }
                }
            }
        }
    }

    // ---- Add box ----

    fun setAddText(text: String) = _state.update { it.copy(add = it.add.copy(text = text, error = null)) }

    /** Toggle a tag chip of the add box. */
    fun toggleAddTag(id: TagId) = _state.update { s ->
        val ids = s.add.tagIds
        s.copy(add = s.add.copy(tagIds = if (id in ids) ids - id else ids + id))
    }

    /** Submit the add box: split on commas and newlines, validate locally, then POST the batch. */
    fun submitAdd() {
        val add = _state.value.add
        if (add.busy) return
        val words = when (val plan = planAdd(add.text)) {
            AddPlan.Nothing -> return
            is AddPlan.Invalid, is AddPlan.TooMany -> return _state.update { it.copy(add = it.add.copy(error = plan.problemMessage())) }
            is AddPlan.Ready -> plan.words.map { it.value }
        }
        _state.update { it.copy(add = it.add.copy(busy = true, error = null)) }
        viewModelScope.launch {
            when (val result = repo.add(words, add.tagIds)) {
                is ApiResult.Success -> {
                    storeAdded(result.value)
                    _state.update { it.copy(add = it.add.copy(text = "", busy = false)) }
                    message(addSummary(result.value.created.size, result.value.existing.size))
                }
                is ApiResult.Failure -> {
                    val error = toWordBankError(result.error)
                    val text = (error as? WordBankError.InvalidWord)?.index?.let { i -> words.getOrNull(i)?.let { "The server rejected word ${i + 1} (\"$it\")." } }
                        ?: error.userMessage()
                    _state.update { it.copy(add = it.add.copy(busy = false, error = text)) }
                }
            }
        }
    }

    /** New words go to the top; existing ones (maybe with new tags) are replaced in place. */
    private fun storeAdded(response: WordBankAddResponse) {
        val added = response.created + response.existing
        updateWords { it.mergedWith(added) }
        markRelatedInBank(added)
    }

    // ---- Selection ----

    fun toggleSelected(id: BankWordId) = _state.update { s ->
        if (s.visible.none { it.id == id }) return@update s
        s.copy(selection = if (id in s.selection) s.selection - id else s.selection + id)
    }

    /** Long press: select (entering selection mode). */
    fun select(id: BankWordId) = selectAll(listOf(id))

    /** Add [ids] (a family's "select all", rhyme matches) to the selection; hidden words are skipped. */
    fun selectAll(ids: Collection<BankWordId>) = _state.update { s ->
        val shown = s.visible.mapTo(HashSet()) { it.id }
        s.copy(selection = s.selection + ids.filter { it in shown })
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ---- Rename ----

    fun startRename(id: BankWordId) {
        val word = words().firstOrNull { it.id == id } ?: return
        _state.update { it.copy(rename = RenameSession(id, word.text)) }
    }

    fun setRenameText(text: String) = _state.update { s -> s.copy(rename = s.rename?.copy(text = text, error = null)) }

    fun cancelRename() = _state.update { it.copy(rename = null) }

    private inline fun updateRename(change: (RenameSession) -> RenameSession) = _state.update { s -> s.copy(rename = s.rename?.let(change)) }

    fun submitRename() {
        val session = _state.value.rename ?: return
        if (session.busy) return
        val text = when (val check = validateWordText(session.text)) {
            is WordTextCheck.Invalid -> return updateRename { it.copy(error = check.problem.userMessage()) }
            is WordTextCheck.Valid -> check.text.value
        }
        if (words().firstOrNull { it.id == session.id }?.text == text) return cancelRename()
        updateRename { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repo.rename(session.id, text)) {
                is ApiResult.Success -> {
                    updateWords { it.mergedWith(listOf(result.value)) }
                    _state.update { it.copy(rename = null) }
                }
                is ApiResult.Failure -> when (val error = toWordBankError(result.error)) {
                    WordBankError.WordNotFound -> {
                        dropWords(setOf(session.id))
                        _state.update { it.copy(rename = null) }
                        message(error.userMessage())
                    }
                    else -> updateRename { it.copy(busy = false, error = error.userMessage()) }
                }
            }
        }
    }

    // ---- Delete ----

    /** Delete the selection (the UI confirms first when more than five words are selected). */
    fun deleteSelected() {
        val s = _state.value
        if (s.deleting) return
        val ids = s.selectedWords.map { it.id }
        if (ids.isEmpty()) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val deleted = HashSet<BankWordId>()
            var failure: WordBankError? = null
            for (chunk in ids.chunked(MAX_IDS_PER_REQUEST)) {
                when (val result = repo.delete(chunk)) {
                    is ApiResult.Success -> deleted += chunk
                    is ApiResult.Failure -> {
                        failure = toWordBankError(result.error)
                        break
                    }
                }
            }
            dropWords(deleted)
            _state.update { it.copy(deleting = false) }
            failure?.let { message(it.userMessage()) }
        }
    }

    private fun dropWords(ids: Set<BankWordId>) {
        if (ids.isEmpty()) return
        updateWords { it.withoutIds(ids) }
        _state.update { s ->
            val status = s.related.status
            val related = if (status is RelatedStatus.Loaded) {
                val results = status.response.results.map { if (it.bankWordId in ids) it.copy(bankWordId = null) else it }
                s.related.copy(status = RelatedStatus.Loaded(status.response.copy(results = results)))
            } else {
                s.related
            }
            s.copy(related = related)
        }
    }

    // ---- Tabs and rhymes ----

    fun setTab(tab: WordBankTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        if (tab == WordBankTab.RHYMES) refreshRhymeView()
    }

    fun setRhymeView(view: RhymeView) {
        _state.update { it.copy(rhymeView = view) }
        refreshRhymeView()
    }

    /** (Re)load what the rhymes tab shows. */
    fun refreshRhymeView() = when (_state.value.rhymeView) {
        RhymeView.FAMILIES -> loadFamilies()
        RhymeView.RHYMES_WITH -> loadRhymesWith()
    }

    /** The slider moves; the query runs on release ([loadFamilies]). */
    fun setFamiliesMinScore(score: Double) = _state.update { it.copy(families = it.families.copy(minScore = score.coerceIn(0.0, 1.0))) }

    fun loadFamilies() {
        val s = _state.value
        val request = BankFamiliesRequest(tagFilter = tagFilterParam(s.filter), minScore = s.families.minScore)
        familiesJob?.cancel()
        _state.update { it.copy(families = it.families.copy(result = Remote.Loading)) }
        familiesJob = viewModelScope.launch {
            val result = when (val r = repo.families(request)) {
                is ApiResult.Success -> Remote.Loaded(r.value)
                is ApiResult.Failure -> Remote.Failed(toWordBankError(r.error))
            }
            _state.update { it.copy(families = it.families.copy(result = result)) }
        }
    }

    fun setRhymesTyped(text: String) = _state.update { it.copy(rhymesWith = it.rhymesWith.copy(typed = text)) }

    fun setRhymesMinScore(score: Double) = _state.update { it.copy(rhymesWith = it.rhymesWith.copy(minScore = score.coerceIn(0.0, 1.0))) }

    /** Rank the filtered bank against the typed text, else the one selected word; neither: ask for one. */
    fun loadRhymesWith() {
        val s = _state.value
        rhymesJob?.cancel()
        val anchor = rhymesWithAnchor(s.rhymesWith.typed, s.selection)
        if (anchor == null) {
            _state.update { it.copy(rhymesWith = it.rhymesWith.copy(result = Remote.Idle, needsAnchor = true)) }
            return
        }
        val request = BankRhymesRequest.of(anchor, tagFilter = tagFilterParam(s.filter), minScore = s.rhymesWith.minScore)
        _state.update { it.copy(rhymesWith = it.rhymesWith.copy(result = Remote.Loading, needsAnchor = false)) }
        rhymesJob = viewModelScope.launch {
            val result = when (val r = repo.rhymes(request)) {
                is ApiResult.Success -> Remote.Loaded(r.value)
                is ApiResult.Failure -> Remote.Failed(toWordBankError(r.error))
            }
            _state.update { it.copy(rhymesWith = it.rhymesWith.copy(result = result)) }
        }
    }

    // ---- Related ----

    fun setRelatedQuery(query: String) = _state.update { it.copy(related = it.related.copy(query = query)) }

    /**
     * Search related words. While the index warms, retry after `Retry-After` (at most
     * [dev.nymph.lyrics.logic.wordbank.MAX_RELATED_RETRIES] retries); a new search replaces a waiting one.
     */
    fun searchRelated() {
        relatedJob?.cancel()
        val query = when (val check = checkRelatedQuery(_state.value.related.query)) {
            is RelatedQueryCheck.Ok -> check.query
            else -> return setRelatedStatus(RelatedStatus.Invalid(checkNotNull(check.problemMessage())))
        }
        setRelatedStatus(RelatedStatus.Loading)
        relatedJob = viewModelScope.launch {
            var retries = 0
            while (true) {
                when (val result = repo.related(query)) {
                    is ApiResult.Success -> return@launch setRelatedStatus(RelatedStatus.Loaded(result.value))
                    is ApiResult.Failure -> {
                        val error = toWordBankError(result.error)
                        val wait = (error as? WordBankError.RelatedWarming)?.let { relatedRetryDelayMs(retries, it.retryAfterSeconds) }
                            ?: return@launch setRelatedStatus(RelatedStatus.Failed(error))
                        setRelatedStatus(RelatedStatus.Warming(retries))
                        delay(wait)
                        retries++
                    }
                }
            }
        }
    }

    private fun setRelatedStatus(status: RelatedStatus) = _state.update { it.copy(related = it.related.copy(status = status)) }

    /** "+" on a related result: add it with the add box's tags. */
    fun addRelated(text: String) {
        val s = _state.value
        if (text in s.related.adding) return
        val word = when (val check = validateWordText(text)) {
            is WordTextCheck.Invalid -> return message("\"$text\": ${check.problem.userMessage()}")
            is WordTextCheck.Valid -> check.text.value
        }
        _state.update { it.copy(related = it.related.copy(adding = it.related.adding + text)) }
        viewModelScope.launch {
            when (val result = repo.add(listOf(word), s.add.tagIds)) {
                is ApiResult.Success -> {
                    storeAdded(result.value)
                    message(addSummary(result.value.created.size, result.value.existing.size))
                }
                is ApiResult.Failure -> message(toWordBankError(result.error).userMessage())
            }
            _state.update { it.copy(related = it.related.copy(adding = it.related.adding - text)) }
        }
    }

    /** Related results the user now has show "in bank". */
    private fun markRelatedInBank(words: List<BankWord>) = _state.update { s ->
        val status = s.related.status as? RelatedStatus.Loaded ?: return@update s
        val byLower = words.associateBy { it.text.lowercase(Locale.ROOT) }
        val results = status.response.results.map { r -> byLower[r.text.lowercase(Locale.ROOT)]?.let { r.copy(bankWordId = it.id) } ?: r }
        s.copy(related = s.related.copy(status = RelatedStatus.Loaded(status.response.copy(results = results))))
    }

    // ---- Snippet draft ----

    /** "Make snippet": the selected words in list order, one per line, freestyle. */
    fun openDraft() {
        val s = _state.value
        if (s.selection.isEmpty()) return
        _state.update { it.copy(draft = DraftState(draftText(s.allWords, s.selection))) }
    }

    fun setDraftText(text: String) = _state.update { s -> s.copy(draft = s.draft?.copy(text = text, error = null)) }

    fun setDraftType(type: SnippetType) = _state.update { s -> s.copy(draft = s.draft?.copy(type = type)) }

    /** Cancel discards the draft. */
    fun cancelDraft() = _state.update { it.copy(draft = null) }

    /** Create the draft on the canvas; the words stay in the bank. */
    fun createDraft() {
        val draft = _state.value.draft ?: return
        if (draft.busy) return
        if (draft.text.isBlank()) return _state.update { s -> s.copy(draft = s.draft?.copy(error = "The draft is empty.")) }
        _state.update { s -> s.copy(draft = s.draft?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val failure = when (val result = canvas.createSnippetFromText(draft.text, draft.type)) {
                is DraftCreateResult.Created -> null
                is DraftCreateResult.Failed -> result.error.userMessage()
                DraftCreateResult.CanvasNotLoaded -> "The canvas has not loaded yet. Go back and try again."
            }
            if (failure == null) {
                _state.update { it.copy(draft = null) }
                message("Snippet created on the canvas")
            } else {
                _state.update { s -> s.copy(draft = s.draft?.copy(busy = false, error = failure)) }
            }
        }
    }

    // ---- TagTarget ----

    override fun currentLinks(ids: Set<BankWordId>): Map<BankWordId, Set<TagId>> = wordTagLinks(words(), ids)

    override suspend fun applyToggle(tag: UserTag, toggle: TagToggle): String? {
        val op = when (toggle) {
            is TagToggle.Link -> TagLinkOp.LINK
            is TagToggle.Unlink -> TagLinkOp.UNLINK
        }
        for (chunk in toggle.targetsOf<BankWordId>().chunked(MAX_IDS_PER_REQUEST)) {
            when (val result = repo.setTag(chunk, tag.id, op)) {
                is ApiResult.Success -> updateWords { it.mergedWith(result.value) }
                is ApiResult.Failure -> {
                    val error = toWordBankError(result.error)
                    // Some word is gone: nothing changed on the server; re-read the bank.
                    if (error == WordBankError.WordNotFound) reload()
                    return error.userMessage()
                }
            }
        }
        return null
    }

    override fun setTagFilter(filter: TagFilter) {
        val before = _state.value.filter
        _state.update { s -> s.copy(filter = filter, selection = pruneSelection(s.selection, visibleWords(s.allWords, filter))) }
        if (filter != before && _state.value.tab == WordBankTab.RHYMES) refreshRhymeView()
    }

    override fun onTagEdited(tag: Tag) = updateWords { it.withTagEdited(tag) }

    override fun onTagDeleted(tag: TagId) {
        updateWords { it.withTagDeleted(tag) }
        _state.update { it.copy(add = it.add.copy(tagIds = it.add.tagIds - tag)) }
    }
}
