package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.SnippetId
import dev.nymph.lyrics.model.TagCreateRequest
import dev.nymph.lyrics.model.TagId
import dev.nymph.lyrics.model.TagLinkResponse
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.UserTag
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.builtins.ListSerializer

/** The user's tags (user-wide, shared by every canvas) and their links to snippets. */
interface TagRepository {
    /** `GET /api/tags`, sorted by lowercase name. */
    suspend fun list(): ApiResult<List<UserTag>>

    /** `POST /api/tags`; a null [color] takes the server default. */
    suspend fun create(name: String, color: String? = null): ApiResult<UserTag>

    /** `PUT /api/tags/{id}`; a rename also rewrites the user's presets on the server. */
    suspend fun update(id: TagId, body: TagUpdateRequest): ApiResult<UserTag>

    suspend fun delete(id: TagId): ApiResult<Unit>

    /** `POST /api/snippets/{snippet}/tags?tag_id={tag}`. */
    suspend fun link(snippet: SnippetId, tag: TagId): ApiResult<Unit>

    /** `DELETE /api/snippets/{snippet}/tags/{tag}`. */
    suspend fun unlink(snippet: SnippetId, tag: TagId): ApiResult<Unit>

    /** The tags as they change (local copy); a server-only repository emits nothing. */
    fun observeTags(): Flow<List<UserTag>> = emptyFlow()
}

class HttpTagRepository(private val api: ApiClient) : TagRepository {
    override suspend fun list(): ApiResult<List<UserTag>> = api.get(listOf("api", "tags"), ListSerializer(UserTag.serializer()))

    override suspend fun create(name: String, color: String?): ApiResult<UserTag> =
        api.send(HttpMethod.POST, listOf("api", "tags"), TagCreateRequest(name, color), TagCreateRequest.serializer(), UserTag.serializer())

    override suspend fun update(id: TagId, body: TagUpdateRequest): ApiResult<UserTag> =
        api.send(HttpMethod.PUT, listOf("api", "tags", id.value), body, TagUpdateRequest.serializer(), UserTag.serializer())

    override suspend fun delete(id: TagId): ApiResult<Unit> = api.delete(listOf("api", "tags", id.value))

    override suspend fun link(snippet: SnippetId, tag: TagId): ApiResult<Unit> = api.sendQuery(
        HttpMethod.POST,
        listOf("api", "snippets", snippet.value, "tags"),
        listOf("tag_id" to tag.value),
        TagLinkResponse.serializer(),
    ).map { }

    override suspend fun unlink(snippet: SnippetId, tag: TagId): ApiResult<Unit> =
        api.delete(listOf("api", "snippets", snippet.value, "tags", tag.value))
}
