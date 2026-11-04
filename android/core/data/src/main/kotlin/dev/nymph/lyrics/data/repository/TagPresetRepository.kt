package dev.nymph.lyrics.data.repository

import dev.nymph.lyrics.data.ApiClient
import dev.nymph.lyrics.data.HttpMethod
import dev.nymph.lyrics.model.TagPreset
import dev.nymph.lyrics.model.TagPresetCreateRequest
import dev.nymph.lyrics.model.TagPresetId
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.error.ApiResult
import kotlinx.serialization.builtins.ListSerializer

/** The user's saved filter expressions (server-side, synced across devices). */
interface TagPresetRepository {
    /** `GET /api/tag-presets`, sorted by lowercase name. */
    suspend fun list(): ApiResult<List<TagPreset>>

    suspend fun create(name: String, expression: String): ApiResult<TagPreset>

    suspend fun update(id: TagPresetId, body: TagPresetUpdateRequest): ApiResult<TagPreset>

    suspend fun delete(id: TagPresetId): ApiResult<Unit>
}

class HttpTagPresetRepository(private val api: ApiClient) : TagPresetRepository {
    override suspend fun list(): ApiResult<List<TagPreset>> = api.get(listOf("api", "tag-presets"), ListSerializer(TagPreset.serializer()))

    override suspend fun create(name: String, expression: String): ApiResult<TagPreset> = api.send(
        HttpMethod.POST,
        listOf("api", "tag-presets"),
        TagPresetCreateRequest(name, expression),
        TagPresetCreateRequest.serializer(),
        TagPreset.serializer(),
    )

    override suspend fun update(id: TagPresetId, body: TagPresetUpdateRequest): ApiResult<TagPreset> =
        api.send(HttpMethod.PUT, listOf("api", "tag-presets", id.value), body, TagPresetUpdateRequest.serializer(), TagPreset.serializer())

    override suspend fun delete(id: TagPresetId): ApiResult<Unit> = api.delete(listOf("api", "tag-presets", id.value))
}
