package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors of the rhyme-pattern contract (backend/app/schemas/rhyme_pattern.py): slots where 2+
 * consecutive lines of one snippet rhyme with each other, and per-slot suggestions.
 * Line indexes are absolute in the snippet; word indexes count words only (rests excluded).
 */
object PatternLimits {
    /** A pattern range holds 2..16 lines. */
    const val MIN_LINES: Int = 2
    const val MAX_LINES: Int = 16

    /** At most this many slots per suggest request. */
    const val MAX_SLOTS: Int = 8

    /** Candidates per slot page (the request `limit`) and line matches per page. */
    const val PAGE_SIZE: Int = 30

    /** Detection threshold default (`PatternDetectRequest.min_score`), and its bounds. */
    const val DETECT_MIN_SCORE: Double = 0.75
    const val DETECT_MIN_SCORE_FLOOR: Double = 0.3
}

/** A word range `[wordStart, wordEnd)` of one snippet line. */
@Serializable
data class SpanRef(
    @SerialName("line_index") val lineIndex: Int,
    @SerialName("word_start") val wordStart: Int,
    @SerialName("word_end") val wordEnd: Int,
)

/** A slot member as the server echoes it (`PatternSpanModel`). */
@Serializable
data class PatternSpan(
    @SerialName("line_index") val lineIndex: Int,
    @SerialName("word_start") val wordStart: Int,
    @SerialName("word_end") val wordEnd: Int,
    val text: String,
    @SerialName("syllable_count") val syllableCount: Int,
    val stress: String,
    @SerialName("rhyme_key") val rhymeKey: List<String>,
    val syllables: List<RhymeSyllable>,
) {
    val ref: SpanRef get() = SpanRef(lineIndex, wordStart, wordEnd)
}

/** A detected or recomputed slot (`PatternSlotModel`); members in line order. */
@Serializable
data class PatternSlot(
    val id: String,
    val members: List<PatternSpan>,
    val coverage: Int,
    val full: Boolean,
    @SerialName("default_on") val defaultOn: Boolean,
    val k: RhymeK,
    /** Min pairwise member score (1.0 for a single member). */
    val cohesion: Double,
    @SerialName("syllable_count") val syllableCount: Int,
    val stress: String,
)

@Serializable
data class PatternWord(
    @SerialName("word_index") val wordIndex: Int,
    val text: String,
    @SerialName("syllable_count") val syllableCount: Int,
    val stress: String,
)

/** One pattern line (`PatternLineModel`). */
@Serializable
data class PatternLine(
    @SerialName("line_index") val lineIndex: Int,
    val text: String,
    val words: List<PatternWord>,
    @SerialName("grid_line") val gridLine: GridLine,
)

/** Body of `POST /api/nlp/rhyme-pattern/detect`. */
@Serializable
data class PatternDetectRequest(
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("line_start") val lineStart: Int,
    /** Exclusive. */
    @SerialName("line_end") val lineEnd: Int,
    @SerialName("min_score") val minScore: Double = PatternLimits.DETECT_MIN_SCORE,
    val weights: ScoreWeights? = null,
    val dialect: DialectMap? = null,
)

@Serializable
data class PatternDetectResponse(
    val lines: List<PatternLine>,
    /** Empty when the lines share no rhyme at the threshold. */
    val slots: List<PatternSlot>,
    @SerialName("elapsed_ms") val elapsedMs: Double,
)

/** A slot as the client sends it; [id] is echoed back, [k] null = derived by the server. */
@Serializable
data class SlotSpec(
    val id: String? = null,
    val members: List<SpanRef>,
    val k: RhymeK? = null,
)

/** Body of `POST /api/nlp/rhyme-pattern/suggest`. */
@Serializable
data class PatternSuggestRequest(
    @SerialName("canvas_id") val canvasId: CanvasId,
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("line_start") val lineStart: Int,
    @SerialName("line_end") val lineEnd: Int,
    /** 1..8, in slot order. */
    val slots: List<SlotSpec>,
    @SerialName("min_score") val minScore: Double = 0.5,
    val sources: List<SourceName> = listOf(SourceName.NOTES, SourceName.DICTIONARY),
    @SerialName("include_rare") val includeRare: Boolean = false,
    @SerialName("tag_filter") val tagFilter: String? = null,
    @SerialName("exclude_ids") val excludeIds: List<String> = emptyList(),
    /** Per slot. */
    val limit: Int = PatternLimits.PAGE_SIZE,
    /** Slot id -> offset (paging one slot). */
    val offsets: Map<String, Int> = emptyMap(),
    @SerialName("line_matches") val lineMatches: Boolean = true,
    @SerialName("line_match_limit") val lineMatchLimit: Int = PatternLimits.PAGE_SIZE,
    @SerialName("line_match_offset") val lineMatchOffset: Int = 0,
    val weights: ScoreWeights? = null,
    val dialect: DialectMap? = null,
)

/**
 * A per-slot candidate (`PatternCandidateModel`, a RhymeCandidateModel plus [memberScores]).
 * `score` = min(memberScores); `bestAnchorId` is the weakest member's span id
 * (`pattern:<snippet_id>:<line>:<start>-<end>`); `fitsSlot` is against the slot's stress.
 */
@Serializable
data class PatternCandidate(
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
    /** Score against each slot member, in member order. */
    @SerialName("member_scores") val memberScores: List<Double>,
) : RhymeUnitFields

@Serializable
data class PatternSlotResult(
    /** Recomputed from the members sent. */
    val slot: PatternSlot,
    val candidates: List<PatternCandidate>,
    val total: Int,
)

/** Where one slot hits inside a whole-line match. */
@Serializable
data class LineMatchHit(
    @SerialName("slot_id") val slotId: String,
    @SerialName("word_start") val wordStart: Int,
    @SerialName("word_end") val wordEnd: Int,
    val text: String,
    val score: Double,
)

/** One of the user's lines that already hits 2+ slots in order. */
@Serializable
data class LineMatch(
    /** The note line (`note:<sid>:<line>:0`). */
    val unit: RhymeUnit,
    /** In slot order and word order. */
    val hits: List<LineMatchHit>,
    @SerialName("slots_hit") val slotsHit: Int,
    /** Mean of the hit scores. */
    val score: Double,
)

@Serializable
data class PatternSuggestResponse(
    val slots: List<PatternSlotResult>,
    @SerialName("line_matches") val lineMatches: List<LineMatch>,
    @SerialName("line_match_total") val lineMatchTotal: Int,
    @SerialName("elapsed_ms") val elapsedMs: Double,
)

/** The structured `detail.error` codes of the rhyme-pattern routes (422). */
enum class PatternErrorCode(val wire: String) {
    /** Range outside the snippet, fewer than 2 lines with words, or more than 16. */
    RANGE_INVALID("pattern_range_invalid"),

    /** A member outside the range or its line, two members on one line, or members overlapping across slots. */
    SPAN_INVALID("pattern_span_invalid"),

    /** Slot order is inconsistent with word order on some line. */
    SLOT_ORDER("pattern_slot_order"),

    /** The `tag_filter` expression does not parse. */
    INVALID_TAG_EXPRESSION("invalid_tag_expression"),
    ;

    companion object {
        fun parse(code: String?): PatternErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
