package dev.nymph.lyrics.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator

/** Mirrors of the rhyme-suggest contract (backend/app/schemas/rhyme.py, frontend/src/types/rhyme.ts). */
object RhymeLimits {
    /** Candidates per page; the request `limit`. */
    const val PAGE_SIZE: Int = 40
    const val MAX_LIMIT: Int = 100
    /** A slot / unit stress string: one of 0, 1, 2 per syllable. */
    val STRESS_PATTERN: Regex = Regex("^[012]+$")
}

/** Rhyme depth: how many trailing syllables must rhyme. Serialises as the bare integer 1..4. */
@Serializable(with = RhymeKSerializer::class)
enum class RhymeK(val value: Int) {
    K1(1), K2(2), K3(3), K4(4);

    companion object {
        fun of(value: Int): RhymeK? = entries.firstOrNull { it.value == value }
    }
}

internal object RhymeKSerializer : KSerializer<RhymeK> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.nymph.lyrics.model.RhymeK", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: RhymeK) = encoder.encodeInt(value.value)
    override fun deserialize(decoder: Decoder): RhymeK {
        val raw = decoder.decodeInt()
        return RhymeK.of(raw) ?: throw SerializationException("k must be 1..4, got $raw")
    }
}

@Serializable
enum class SourceName {
    @SerialName("notes") NOTES,
    @SerialName("dictionary") DICTIONARY,
}

/** A rhyme query anchor: a snippet line (optionally a word range of it) or typed text. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface AnchorRef

/**
 * A snippet line (`lineIndex == null` = last non-empty line), optionally narrowed to the words
 * `[wordStart, wordEnd)`. Ranges snap to word boundaries.
 */
@Serializable
@SerialName("snippet")
data class SnippetAnchorRef(
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("line_index") val lineIndex: Int? = null,
    @SerialName("word_start") val wordStart: Int? = null,
    /** Exclusive; null = to the end of the line. */
    @SerialName("word_end") val wordEnd: Int? = null,
) : AnchorRef

@Serializable
@SerialName("text")
data class TextAnchorRef(
    val text: String,
) : AnchorRef

/** The part of a selected snippet a rhyme query anchors on (client-side); [wordEnd] is exclusive. */
data class AnchorRange(
    val lineIndex: Int,
    val wordStart: Int,
    val wordEnd: Int,
)

/** A rhythmic slot: the stress string has one character per beat; "0" marks a rest or unstressed beat. */
@Serializable
data class Slot(
    @SerialName("syllable_count") val syllableCount: Int,
    val stress: String,
)

@Serializable
data class ScoreWeights(
    val vowel: Double = 1.0,
    val coda: Double = 0.6,
    val onset: Double = 0.25,
)

@Serializable
enum class DialectRuleKind {
    @SerialName("merge") MERGE,
    @SerialName("final_merge") FINAL_MERGE,
    @SerialName("optional_final") OPTIONAL_FINAL,
}

@Serializable
data class DialectRule(
    val kind: DialectRuleKind,
    val source: List<String>,
    val target: List<String> = emptyList(),
)

@Serializable
data class DialectMap(
    val rules: List<DialectRule> = emptyList(),
)

/** Body of `POST /api/nlp/rhyme-suggest`. */
@Serializable
data class RhymeSuggestRequest(
    @SerialName("canvas_id") val canvasId: CanvasId,
    val anchors: List<AnchorRef>,
    val context: List<AnchorRef> = emptyList(),
    val slot: Slot? = null,
    val k: RhymeK = RhymeK.K2,
    @SerialName("min_score") val minScore: Double = 0.5,
    val sources: List<SourceName> = listOf(SourceName.NOTES, SourceName.DICTIONARY),
    @SerialName("include_rare") val includeRare: Boolean = false,
    @SerialName("exclude_ids") val excludeIds: List<String> = emptyList(),
    val limit: Int = RhymeLimits.PAGE_SIZE,
    val offset: Int = 0,
    val weights: ScoreWeights? = null,
    val dialect: DialectMap? = null,
)

@Serializable
data class RhymeSyllable(
    val onset: List<String>,
    val nucleus: String,
    val coda: List<String>,
    /** 0, 1 or 2. */
    val stress: Int,
)

/** Fields shared by anchor units and candidates (`RhymeUnit` in the TS / pydantic models). */
interface RhymeUnitFields {
    val id: String
    val text: String
    val provenance: Provenance
    val ephemeral: Boolean
    val syllableCount: Int
    val stress: String
    val rhymeKey: List<String>
    val syllables: List<RhymeSyllable>
    /** Renders with the beat-grid cell code. */
    val gridLine: GridLine
    val snippetId: SnippetId?
    val lineIndex: Int?
    /** Note units: first word of the line included; echoes `word_start` for a range anchor. */
    val wordOffset: Int?
    /** Range anchors only: one past the last word included. */
    val wordEnd: Int?
    val tags: List<String>
}

@Serializable
data class RhymeUnit(
    override val id: String,
    override val text: String,
    override val provenance: Provenance,
    override val ephemeral: Boolean,
    @SerialName("syllable_count") override val syllableCount: Int,
    override val stress: String,
    @SerialName("rhyme_key") override val rhymeKey: List<String>,
    override val syllables: List<RhymeSyllable>,
    @SerialName("grid_line") override val gridLine: GridLine,
    @SerialName("snippet_id") override val snippetId: SnippetId? = null,
    @SerialName("line_index") override val lineIndex: Int? = null,
    @SerialName("word_offset") override val wordOffset: Int? = null,
    @SerialName("word_end") override val wordEnd: Int? = null,
    override val tags: List<String> = emptyList(),
) : RhymeUnitFields

/** Per-syllable score breakdown; right-to-left (index 0 is the final syllable). */
@Serializable
data class SyllableAlignment(
    @SerialName("cand_index") val candIndex: Int?,
    @SerialName("anchor_index") val anchorIndex: Int?,
    val nucleus: Double,
    val coda: Double,
    val onset: Double,
    val total: Double,
)

@Serializable
data class RhymeCandidate(
    override val id: String,
    override val text: String,
    override val provenance: Provenance,
    override val ephemeral: Boolean,
    @SerialName("syllable_count") override val syllableCount: Int,
    override val stress: String,
    @SerialName("rhyme_key") override val rhymeKey: List<String>,
    override val syllables: List<RhymeSyllable>,
    @SerialName("grid_line") override val gridLine: GridLine,
    @SerialName("snippet_id") override val snippetId: SnippetId? = null,
    @SerialName("line_index") override val lineIndex: Int? = null,
    @SerialName("word_offset") override val wordOffset: Int? = null,
    @SerialName("word_end") override val wordEnd: Int? = null,
    override val tags: List<String> = emptyList(),
    val score: Double,
    @SerialName("match_depth") val matchDepth: Int,
    @SerialName("best_anchor_id") val bestAnchorId: String,
    @SerialName("fits_slot") val fitsSlot: Boolean,
    val alignment: List<SyllableAlignment>,
    val source: SourceName,
) : RhymeUnitFields

/** Response of `POST /api/nlp/rhyme-suggest`. */
@Serializable
data class RhymeSuggestResponse(
    val anchors: List<RhymeUnit>,
    val candidates: List<RhymeCandidate>,
    val total: Int,
    @SerialName("elapsed_ms") val elapsedMs: Double,
)
