package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.data.repository.TagPresetRepository
import dev.nymph.lyrics.data.repository.TagRepository
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.withTagDeleted
import dev.nymph.lyrics.logic.tags.withTagEdited
import dev.nymph.lyrics.logic.tags.withTagLinked
import dev.nymph.lyrics.logic.tags.withTagUnlinked
import dev.nymph.lyrics.model.CanvasId
import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.Tag
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiResult

fun userTag(name: String, color: String = "#6366f1", count: Int = 0): UserTag = UserTag(TagId("t-$name"), name, color, count, "")

fun preset(name: String, expression: String): TagPreset = TagPreset(TagPresetId("p-$name"), name, expression, "", "")

class FakeTagRepository(var tags: List<UserTag> = emptyList()) : TagRepository {
    var listResult: () -> ApiResult<List<UserTag>> = { ApiResult.Success(tags) }
    var createResult: (String, String?) -> ApiResult<UserTag> = { name, color -> ApiResult.Success(UserTag(TagId("t-$name"), name, color ?: "#6366f1", 0, "")) }
    var updateResult: (TagId, TagUpdateRequest) -> ApiResult<UserTag> = { id, body ->
        val old = tags.first { it.id == id }
        ApiResult.Success(old.copy(name = body.name ?: old.name, color = body.color ?: old.color))
    }
    var deleteResult: (TagId) -> ApiResult<Unit> = { ApiResult.Success(Unit) }
    var linkResult: (SnippetId, TagId) -> ApiResult<Unit> = { _, _ -> ApiResult.Success(Unit) }
    var unlinkResult: (SnippetId, TagId) -> ApiResult<Unit> = { _, _ -> ApiResult.Success(Unit) }

    val created = mutableListOf<Pair<String, String?>>()
    val updates = mutableListOf<Pair<TagId, TagUpdateRequest>>()
    val deleted = mutableListOf<TagId>()
    val links = mutableListOf<Pair<SnippetId, TagId>>()
    val unlinks = mutableListOf<Pair<SnippetId, TagId>>()
    var listCalls = 0

    override suspend fun list(): ApiResult<List<UserTag>> {
        listCalls++
        return listResult()
    }

    override suspend fun create(name: String, color: String?): ApiResult<UserTag> {
        created += name to color
        return createResult(name, color)
    }

    override suspend fun update(id: TagId, body: TagUpdateRequest): ApiResult<UserTag> {
        updates += id to body
        return updateResult(id, body)
    }

    override suspend fun delete(id: TagId): ApiResult<Unit> {
        deleted += id
        return deleteResult(id)
    }

    override suspend fun link(snippet: SnippetId, tag: TagId): ApiResult<Unit> {
        links += snippet to tag
        return linkResult(snippet, tag)
    }

    override suspend fun unlink(snippet: SnippetId, tag: TagId): ApiResult<Unit> {
        unlinks += snippet to tag
        return unlinkResult(snippet, tag)
    }
}

class FakeTagPresetRepository(var presets: List<TagPreset> = emptyList()) : TagPresetRepository {
    var listResult: () -> ApiResult<List<TagPreset>> = { ApiResult.Success(presets) }
    var createResult: (String, String) -> ApiResult<TagPreset> = { name, expression -> ApiResult.Success(preset(name, expression)) }
    var updateResult: (TagPresetId, TagPresetUpdateRequest) -> ApiResult<TagPreset> = { id, body ->
        val old = presets.first { it.id == id }
        ApiResult.Success(old.copy(name = body.name ?: old.name, expression = body.expression ?: old.expression))
    }
    var deleteResult: (TagPresetId) -> ApiResult<Unit> = { ApiResult.Success(Unit) }

    val created = mutableListOf<Pair<String, String>>()
    val updates = mutableListOf<Pair<TagPresetId, TagPresetUpdateRequest>>()
    val deleted = mutableListOf<TagPresetId>()
    var listCalls = 0

    override suspend fun list(): ApiResult<List<TagPreset>> {
        listCalls++
        return listResult()
    }

    override suspend fun create(name: String, expression: String): ApiResult<TagPreset> {
        created += name to expression
        return createResult(name, expression)
    }

    override suspend fun update(id: TagPresetId, body: TagPresetUpdateRequest): ApiResult<TagPreset> {
        updates += id to body
        return updateResult(id, body)
    }

    override suspend fun delete(id: TagPresetId): ApiResult<Unit> {
        deleted += id
        return deleteResult(id)
    }
}

class FakeTagFilterStore(initial: Map<CanvasId, String> = emptyMap()) : TagFilterStore {
    val saved = initial.toMutableMap()
    val saves = mutableListOf<Pair<CanvasId, String>>()

    override suspend fun load(canvasId: CanvasId): String = saved[canvasId] ?: ""

    override suspend fun save(canvasId: CanvasId, text: String) {
        saves += canvasId to text
        saved[canvasId] = text
    }
}

class FakeTagCanvasPort(snippets: List<Snippet>) : TagCanvasPort {
    var snippets: List<Snippet> = snippets
    val filters = mutableListOf<TagFilter>()

    override fun currentSnippets(): Map<SnippetId, Snippet> = snippets.associateBy { it.id }

    override fun setTagFilter(filter: TagFilter) {
        filters += filter
    }

    override fun onTagLinked(snippet: SnippetId, tag: Tag) {
        snippets = snippets.map { if (it.id == snippet) it.withTagLinked(tag) else it }
    }

    override fun onTagUnlinked(snippet: SnippetId, tag: TagId) {
        snippets = snippets.map { if (it.id == snippet) it.withTagUnlinked(tag) else it }
    }

    override fun onTagEdited(tag: Tag) {
        snippets = snippets.withTagEdited(tag)
    }

    override fun onTagDeleted(tag: TagId) {
        snippets = snippets.withTagDeleted(tag)
    }
}
