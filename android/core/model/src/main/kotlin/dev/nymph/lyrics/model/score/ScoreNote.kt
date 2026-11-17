package dev.nymph.lyrics.model.score

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One placed syllable: [s] is the syllable ordinal in the grid line (rest cells not counted), [t] the
 * start tick from tick 0, [d] the duration in ticks (>= 1), [a] the accent (0 none, 1, 2 strong).
 *
 * [extra] holds every other key of the note object (a future pitch) and is written back unchanged,
 * after `s, t, d, a`, so an older client never drops it.
 */
@Serializable(with = ScoreNoteSerializer::class)
data class ScoreNote(
    val s: Int,
    val t: Int,
    val d: Int,
    val a: Int,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val end: Int get() = t + d

    companion object {
        /** The keys this type reads; anything else lands in [extra]. */
        val CORE_KEYS: Set<String> = setOf("s", "t", "d", "a")
    }
}

/** JSON-only serializer keeping unknown note keys in [ScoreNote.extra]. */
object ScoreNoteSerializer : KSerializer<ScoreNote> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.nymph.lyrics.model.score.ScoreNote") {
        element<Int>("s")
        element<Int>("t")
        element<Int>("d")
        element<Int>("a")
    }

    override fun deserialize(decoder: Decoder): ScoreNote {
        val input = decoder as? JsonDecoder ?: throw SerializationException("ScoreNote can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("a score note must be a JSON object")
        return ScoreNote(
            s = obj.integer("s"),
            t = obj.integer("t"),
            d = obj.integer("d"),
            a = obj.integer("a"),
            extra = obj.filterKeys { it !in ScoreNote.CORE_KEYS },
        )
    }

    override fun serialize(encoder: Encoder, value: ScoreNote) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("ScoreNote can only be written as JSON")
        output.encodeJsonElement(
            buildJsonObject {
                put("s", value.s)
                put("t", value.t)
                put("d", value.d)
                put("a", value.a)
                for ((key, element) in value.extra) {
                    if (key !in ScoreNote.CORE_KEYS) put(key, element)
                }
            },
        )
    }

    /** An integral JSON number in Int range (12.0 counts as 12; strings and booleans do not). */
    private fun JsonObject.integer(key: String): Int {
        val primitive = this[key] as? JsonPrimitive
        val value = primitive?.takeUnless { it.isString }?.let { p ->
            p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
        }
        if (value == null || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw SerializationException("score note '$key' must be an integer")
        }
        return value.toInt()
    }
}
