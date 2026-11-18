package dev.nymph.lyrics.model.score

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull

/**
 * A change of a snippet's score in a PUT: [score] is the new score, or null for "Remove score".
 * "No change" is the absence of a [ScoreChange] (a null field), so all three states are distinct.
 *
 * Its own serializer (`{"score": {...}}` / `{}`) is what the offline outbox stores; on the wire a
 * request writes it with [ScoreChangeWireSerializer] (the score object, or an explicit `null`).
 */
@Serializable
data class ScoreChange(val score: Score? = null) {
    companion object {
        val REMOVE: ScoreChange = ScoreChange(null)

        fun set(score: Score): ScoreChange = ScoreChange(score)
    }
}

/** `PUT /api/snippets/{id}` `score`: the score object, or JSON `null` for Remove score. */
object ScoreChangeWireSerializer : KSerializer<ScoreChange> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ScoreChange) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("ScoreChange can only be written as JSON")
        output.encodeJsonElement(value.score?.let { LyricsJson.encodeToJsonElement(Score.serializer(), it) } ?: JsonNull)
    }

    override fun deserialize(decoder: Decoder): ScoreChange {
        val input = decoder as? JsonDecoder ?: throw SerializationException("ScoreChange can only be read from JSON")
        val element = input.decodeJsonElement()
        return if (element is JsonNull) ScoreChange.REMOVE else ScoreChange(input.json.decodeFromJsonElement(Score.serializer(), element))
    }
}
