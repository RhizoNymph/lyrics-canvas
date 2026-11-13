package dev.nymph.lyrics.sync.repo

import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.data.repository.TagRepository
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.onSuccess
import dev.nymph.lyrics.sync.apply.LocalEdits
import dev.nymph.lyrics.sync.store.Views
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The user's tags and their snippet links, local-first. */
class LocalTagRepository(private val local: LocalFirst, private val edits: LocalEdits) : TagRepository {
    override suspend fun list(): ApiResult<List<UserTag>> = local.read { ApiResult.Success(Views.tagList(it)) }

    override suspend fun create(name: String, color: String?): ApiResult<UserTag> = local.edit { edits.createTag(it, name, color) }

    override suspend fun update(id: TagId, body: TagUpdateRequest): ApiResult<UserTag> = local.edit { edits.updateTag(it, id, body) }

    override suspend fun delete(id: TagId): ApiResult<Unit> = local.edit { edits.deleteTag(it, id) }

    override suspend fun link(snippet: SnippetId, tag: TagId): ApiResult<Unit> = local.edit { edits.setSnippetTag(it, snippet, tag, link = true) }

    override suspend fun unlink(snippet: SnippetId, tag: TagId): ApiResult<Unit> = local.edit { edits.setSnippetTag(it, snippet, tag, link = false) }

    override fun observeTags(): Flow<List<UserTag>> = local.store.data.map(Views::tagList).distinctUntilChanged()
}

/**
 * Presets: read from the copy (the snapshot brings them), saved online only. A save while the
 * server is unreachable answers `NeedsConnection`; a confirmed save updates the copy.
 */
class CachedTagPresetRepository(
    private val local: LocalFirst,
    private val http: TagPresetRepository,
    private val gate: OnlineGate,
) : TagPresetRepository {
    override suspend fun list(): ApiResult<List<TagPreset>> = local.read { ApiResult.Success(Views.presets(it)) }

    override suspend fun create(name: String, expression: String): ApiResult<TagPreset> =
        gate.call { http.create(name, expression) }.onSuccess { store(it) }

    override suspend fun update(id: TagPresetId, body: TagPresetUpdateRequest): ApiResult<TagPreset> =
        gate.call { http.update(id, body) }.onSuccess { store(it) }

    override suspend fun delete(id: TagPresetId): ApiResult<Unit> {
        val result = gate.call { http.delete(id) }
        if (result is ApiResult.Success) local.store.update { d -> d.copy(presets = d.presets.filterNot { it.id == id }) to Unit }
        return result
    }

    override fun observePresets(): Flow<List<TagPreset>> = local.store.data.map(Views::presets).distinctUntilChanged()

    private suspend fun store(preset: TagPreset) {
        local.store.update { d -> d.copy(presets = d.presets.filterNot { it.id == preset.id } + preset) to Unit }
    }
}
