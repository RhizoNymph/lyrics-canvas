package dev.nymph.lyrics.app.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.data.repository.TagRepository
import dev.nymph.lyrics.logic.tags.Completion
import dev.nymph.lyrics.logic.tags.FilterBarState
import dev.nymph.lyrics.logic.tags.Suggestion
import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.TagExprParse
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.TagNameCheck
import dev.nymph.lyrics.logic.tags.TagToggle
import dev.nymph.lyrics.logic.tags.format
import dev.nymph.lyrics.logic.tags.planToggle
import dev.nymph.lyrics.logic.tags.tagLinks
import dev.nymph.lyrics.logic.tags.toTagError
import dev.nymph.lyrics.logic.tags.validateTagName
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private fun List<UserTag>.sortedTags(): List<UserTag> = sortedBy { it.name.lowercase(Locale.ROOT) }

private fun List<TagPreset>.sortedPresets(): List<TagPreset> = sortedBy { it.name.lowercase(Locale.ROOT) }

/**
 * The user's tags and presets (user-wide), this canvas's filter bar, and the tag picker. Lives in
 * the canvas back-stack entry next to [port] (the canvas ViewModel), so the editor shares it.
 *
 * Filter bar: every edit goes through [FilterBarState]; when its applied (last valid) filter
 * changes it is pushed to [port] and its text remembered in [filterStore]. Picker toggles and tag
 * edits are applied to the canvas only after the server confirms them. Never throws: failures
 * become state (inline errors) or [messages].
 */
class TagsViewModel(
    private val canvasId: CanvasId,
    private val tags: TagRepository,
    private val presets: TagPresetRepository,
    private val filterStore: TagFilterStore,
    private val port: TagCanvasPort,
) : ViewModel() {
    private val _state = MutableStateFlow(TagsUiState())
    val state: StateFlow<TagsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Snackbar texts. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Texts to remember, written in order (one writer, conflated to the latest). */
    private val toPersist = Channel<String>(Channel.CONFLATED)

    /** Set by the first edit, so a slower restore does not overwrite what the user typed. */
    private var filterTouched = false

    init {
        reload()
        viewModelScope.launch {
            val saved = filterStore.load(canvasId)
            if (!filterTouched && saved.isNotEmpty()) setFilterBar(FilterBarState.restored(saved), persist = false)
        }
        viewModelScope.launch { for (text in toPersist) filterStore.save(canvasId, text) }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }

    /** A failure while the picker is open shows in the sheet; otherwise in the snackbar. */
    private fun report(text: String) {
        if (_state.value.picker != null) updatePicker { it.copy(error = text) } else message(text)
    }

    /** Load tags and presets (in parallel). */
    fun reload() {
        viewModelScope.launch {
            val tagResult = async { tags.list() }
            val presetResult = async { presets.list() }
            val t = tagResult.await()
            val p = presetResult.await()
            _state.update { s ->
                s.copy(
                    tags = t.valueOrNull()?.sortedTags() ?: s.tags,
                    presets = p.valueOrNull()?.sortedPresets() ?: s.presets,
                    loadError = (t.errorOrNull() ?: p.errorOrNull())?.let(::toTagError),
                )
            }
        }
    }

    private fun reloadPresets() {
        viewModelScope.launch {
            presets.list().valueOrNull()?.let { list -> _state.update { it.copy(presets = list.sortedPresets()) } }
        }
    }

    // ---- Filter bar ----

    private fun setFilterBar(next: FilterBarState, persist: Boolean = true) {
        val before = _state.value.filterBar
        _state.update { it.copy(filterBar = next) }
        if (next.applied != before.applied) port.setTagFilter(next.applied)
        if (persist && next.appliedText != before.appliedText) toPersist.trySend(next.appliedText)
    }

    private inline fun editBar(change: (FilterBarState) -> FilterBarState) {
        filterTouched = true
        setFilterBar(change(_state.value.filterBar))
    }

    /** Text and caret (code points) from the filter field. */
    fun editFilter(text: String, caret: Int) = editBar { it.edit(text, caret) }

    fun acceptFilterSuggestion(completion: Completion, suggestion: Suggestion) = editBar { it.accept(completion, suggestion) }

    /** A tag pill tap: insert the tag at the caret, joined with `&`. */
    fun insertFilterTag(name: String) = editBar { it.insertTag(name) }

    fun clearFilter() = editBar { it.clear() }

    fun applyPreset(id: TagPresetId) {
        val preset = _state.value.presets.firstOrNull { it.id == id } ?: return
        editBar { it.applyPreset(preset.expression) }
    }

    // ---- Picker ----

    /** Open the picker for [ids] (the selection, or the editor's snippet). */
    fun openPicker(ids: Set<SnippetId>) {
        if (ids.isEmpty()) return
        _state.update { it.copy(picker = PickerSession(ids, links = tagLinks(port.currentSnippets(), ids))) }
    }

    fun closePicker() = _state.update { it.copy(picker = null, tagEditor = null) }

    fun setPickerQuery(query: String) = _state.update { s -> s.copy(picker = s.picker?.copy(query = query, error = null)) }

    private inline fun updatePicker(change: (PickerSession) -> PickerSession) =
        _state.update { s -> s.copy(picker = s.picker?.let(change)) }

    /** Re-read the picked snippets' tags from the canvas (after a confirmed change). */
    private fun refreshLinks() = updatePicker { it.copy(links = tagLinks(port.currentSnippets(), it.snippetIds)) }

    /** Tap on a row: link the tag to every picked snippet missing it, or (all have it) unlink it from all. */
    fun togglePickerTag(tagId: TagId) {
        val picker = _state.value.picker ?: return
        if (tagId in picker.pending) return
        val tag = _state.value.tags.firstOrNull { it.id == tagId } ?: return
        val toggle = planToggle(tagId, picker.links)
        if (toggle.snippets.isEmpty()) return
        updatePicker { it.copy(pending = it.pending + tagId, error = null) }
        viewModelScope.launch {
            applyToggle(tag, toggle)
            updatePicker { it.copy(pending = it.pending - tagId) }
        }
    }

    /** Send [toggle] for every snippet in parallel; each confirmed one reaches the canvas; the first failure is reported. */
    private suspend fun applyToggle(tag: UserTag, toggle: TagToggle) {
        val results = toggle.snippets.map { id ->
            viewModelScope.async {
                id to when (toggle) {
                    is TagToggle.Link -> tags.link(id, tag.id)
                    is TagToggle.Unlink -> tags.unlink(id, tag.id)
                }
            }
        }.awaitAll()
        for ((id, result) in results) {
            if (result !is ApiResult.Success) continue
            when (toggle) {
                is TagToggle.Link -> port.onTagLinked(id, tag.toTag())
                is TagToggle.Unlink -> port.onTagUnlinked(id, tag.id)
            }
        }
        refreshLinks()
        results.firstNotNullOfOrNull { it.second.errorOrNull() }?.let { report(toTagError(it).userMessage()) }
    }

    /** "Create '<query>'": create the tag, then link it to every picked snippet. */
    fun createTagFromPicker() {
        val s = _state.value
        val picker = s.picker ?: return
        if (picker.creating) return
        val name = s.createOffer ?: return
        updatePicker { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val result = tags.create(name)) {
                is ApiResult.Success -> {
                    val created = result.value
                    _state.update { it.copy(tags = (it.tags.filter { t -> t.id != created.id } + created).sortedTags()) }
                    updatePicker { it.copy(creating = false, query = "") }
                    val links = _state.value.picker?.links ?: return@launch
                    applyToggle(created, planToggle(created.id, links))
                }
                is ApiResult.Failure -> updatePicker { it.copy(creating = false, error = toTagError(result.error).userMessage()) }
            }
        }
    }

    // ---- Editing tags ----

    fun startEditTag(tagId: TagId) {
        val tag = _state.value.tags.firstOrNull { it.id == tagId } ?: return
        _state.update { it.copy(tagEditor = TagEditor(tag.id, tag.name, tag.color)) }
    }

    fun setTagEditorName(name: String) = _state.update { s -> s.copy(tagEditor = s.tagEditor?.copy(name = name, error = null)) }

    fun setTagEditorColor(color: String) = _state.update { s -> s.copy(tagEditor = s.tagEditor?.copy(color = color, error = null)) }

    fun cancelTagEditor() = _state.update { it.copy(tagEditor = null) }

    private inline fun updateTagEditor(change: (TagEditor) -> TagEditor) =
        _state.update { s -> s.copy(tagEditor = s.tagEditor?.let(change)) }

    /** PUT only what changed; a rename also rewrites the filter here and the presets on the server (reloaded). */
    fun saveTagEditor() {
        val editor = _state.value.tagEditor ?: return
        if (editor.busy) return
        val old = _state.value.tags.firstOrNull { it.id == editor.tagId } ?: return
        val name = when (val check = validateTagName(editor.name)) {
            is TagNameCheck.Invalid -> return updateTagEditor { it.copy(error = check.problem.userMessage()) }
            is TagNameCheck.Ok -> check.name
        }
        val body = TagUpdateRequest(name = name.takeIf { it != old.name }, color = editor.color.takeIf { it != old.color })
        if (body.name == null && body.color == null) return cancelTagEditor()
        updateTagEditor { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = tags.update(old.id, body)) {
                is ApiResult.Success -> {
                    val updated = result.value
                    _state.update { s -> s.copy(tags = s.tags.map { if (it.id == updated.id) updated else it }.sortedTags(), tagEditor = null) }
                    port.onTagEdited(updated.toTag())
                    refreshLinks()
                    if (updated.name != old.name) {
                        _state.value.filterBar.renameTag(old.name, updated.name)?.let { setFilterBar(it) }
                        reloadPresets()
                    }
                }
                is ApiResult.Failure -> updateTagEditor { it.copy(busy = false, error = toTagError(result.error).userMessage()) }
            }
        }
    }

    /** Delete the tag everywhere (after the UI confirmed). Presets keep the name; it matches nothing. */
    fun deleteTag(tagId: TagId) {
        viewModelScope.launch {
            val result = tags.delete(tagId)
            val error = result.errorOrNull()?.let(::toTagError)
            val gone = error == null || (error is TagError.Rejected && error.code == TagErrorCode.TAG_NOT_FOUND)
            if (gone) {
                _state.update { s -> s.copy(tags = s.tags.filter { it.id != tagId }, tagEditor = s.tagEditor?.takeIf { it.tagId != tagId }) }
                port.onTagDeleted(tagId)
                refreshLinks()
            }
            error?.let { report(it.userMessage()) }
        }
    }

    // ---- Presets ----

    /** The current filter as a preset expression, or why it cannot be one (decided before any request). */
    private sealed interface PresetExpression {
        data class Ok(val normalized: String) : PresetExpression

        data class Refused(val message: String) : PresetExpression
    }

    private fun presetExpression(): PresetExpression = when (val parse = _state.value.filterBar.input.parse) {
        is TagExprParse.Invalid -> PresetExpression.Refused(invalidFilterMessage(parse.error))
        is TagExprParse.Valid -> when (val filter = parse.filter) {
            TagFilter.Everything -> PresetExpression.Refused(EMPTY_PRESET_MESSAGE)
            is TagFilter.Where -> PresetExpression.Ok(filter.format())
        }
    }

    fun startSaveAsPreset() = _state.update { it.copy(presetEditor = PresetEditor(PresetEditor.Target.SaveAs, "")) }

    fun startRenamePreset(id: TagPresetId) {
        val preset = _state.value.presets.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(presetEditor = PresetEditor(PresetEditor.Target.Rename(id), preset.name)) }
    }

    fun setPresetName(name: String) = _state.update { s -> s.copy(presetEditor = s.presetEditor?.copy(name = name, error = null)) }

    fun cancelPresetEditor() = _state.update { it.copy(presetEditor = null) }

    private inline fun updatePresetEditor(change: (PresetEditor) -> PresetEditor) =
        _state.update { s -> s.copy(presetEditor = s.presetEditor?.let(change)) }

    /** Save as (POST the current filter) or rename (PUT the name); a taken name shows inline. */
    fun submitPresetEditor() {
        val editor = _state.value.presetEditor ?: return
        if (editor.busy) return
        val name = when (val check = validateTagName(editor.name)) {
            is TagNameCheck.Invalid -> return updatePresetEditor { it.copy(error = check.problem.userMessage()) }
            is TagNameCheck.Ok -> check.name
        }
        val target = editor.target
        val expression = if (target is PresetEditor.Target.SaveAs) {
            when (val e = presetExpression()) {
                is PresetExpression.Refused -> return updatePresetEditor { it.copy(error = e.message) }
                is PresetExpression.Ok -> e.normalized
            }
        } else {
            null
        }
        updatePresetEditor { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = when (target) {
                PresetEditor.Target.SaveAs -> presets.create(name, checkNotNull(expression))
                is PresetEditor.Target.Rename -> presets.update(target.id, TagPresetUpdateRequest(name = name))
            }
            when (result) {
                is ApiResult.Success -> {
                    storePreset(result.value)
                    _state.update { it.copy(presetEditor = null) }
                }
                is ApiResult.Failure -> {
                    val error = toTagError(result.error)
                    dropIfMissing(error, (target as? PresetEditor.Target.Rename)?.id)
                    updatePresetEditor { it.copy(busy = false, error = error.userMessage()) }
                }
            }
        }
    }

    /** Replace the preset's expression with the current filter. */
    fun overwritePreset(id: TagPresetId) {
        val expression = when (val e = presetExpression()) {
            is PresetExpression.Refused -> return message(e.message)
            is PresetExpression.Ok -> e.normalized
        }
        viewModelScope.launch {
            when (val result = presets.update(id, TagPresetUpdateRequest(expression = expression))) {
                is ApiResult.Success -> storePreset(result.value)
                is ApiResult.Failure -> toTagError(result.error).let {
                    dropIfMissing(it, id)
                    message(it.userMessage())
                }
            }
        }
    }

    /** Delete the preset (after the UI confirmed). */
    fun deletePreset(id: TagPresetId) {
        viewModelScope.launch {
            when (val result = presets.delete(id)) {
                is ApiResult.Success -> _state.update { s -> s.copy(presets = s.presets.filter { it.id != id }) }
                is ApiResult.Failure -> toTagError(result.error).let {
                    dropIfMissing(it, id)
                    message(it.userMessage())
                }
            }
        }
    }

    private fun storePreset(preset: TagPreset) =
        _state.update { s -> s.copy(presets = (s.presets.filter { it.id != preset.id } + preset).sortedPresets()) }

    /** A preset the server no longer has leaves the list too. */
    private fun dropIfMissing(error: TagError, id: TagPresetId?) {
        if (id != null && error is TagError.Rejected && error.code == TagErrorCode.PRESET_NOT_FOUND) {
            _state.update { s -> s.copy(presets = s.presets.filter { it.id != id }) }
        }
    }
}
