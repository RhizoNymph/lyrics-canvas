package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.logic.tags.FilterBarState
import dev.nymph.lyrics.logic.tags.PickerRow
import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.createOffer
import dev.nymph.lyrics.logic.tags.pickerRows
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.UserTag

/** The open tag picker: which snippets it edits and their current tag ids. */
data class PickerSession(
    val snippetIds: Set<SnippetId>,
    val query: String = "",
    /** Tag ids of each picked snippet, as the server confirmed them. */
    val links: Map<SnippetId, Set<TagId>>,
    /** Tags with a link / unlink in flight (their rows are disabled). */
    val pending: Set<TagId> = emptySet(),
    /** A create from the query is in flight. */
    val creating: Boolean = false,
    /** The last create failure, shown in the sheet. */
    val error: String? = null,
)

/** Rename / recolour of one tag. */
data class TagEditor(
    val tagId: TagId,
    val name: String,
    val color: String,
    val busy: Boolean = false,
    val error: String? = null,
)

/** The preset name prompt: save the filter as a new preset, or rename one. */
data class PresetEditor(
    val target: Target,
    val name: String,
    val busy: Boolean = false,
    val error: String? = null,
) {
    sealed interface Target {
        data object SaveAs : Target

        data class Rename(val id: TagPresetId) : Target
    }
}

/** User-wide tags and presets, plus this canvas's filter bar and tag picker. */
data class TagsUiState(
    /** Sorted by lowercase name. */
    val tags: List<UserTag> = emptyList(),
    /** Sorted by lowercase name. */
    val presets: List<TagPreset> = emptyList(),
    val loadError: TagError? = null,
    val filterBar: FilterBarState = FilterBarState.restored(""),
    val picker: PickerSession? = null,
    val tagEditor: TagEditor? = null,
    val presetEditor: PresetEditor? = null,
) {
    /** Tag names for completion. */
    val tagNames: List<String> get() = tags.map { it.name }

    val pickerRows: List<PickerRow> get() = picker?.let { pickerRows(tags, it.links, it.query) } ?: emptyList()

    /** The name "Create '…'" offers for the picker query, or null. */
    val createOffer: String? get() = picker?.let { createOffer(tags, it.query) }
}
