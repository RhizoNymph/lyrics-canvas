package dev.nymph.lyrics.model.voice

import dev.nymph.lyrics.model.Snippet
import dev.nymph.lyrics.model.SnippetId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Server-assigned recording id (a UUID string); serialises as a bare JSON string. */
@JvmInline
@Serializable
value class RecordingId(val value: String) {
    override fun toString(): String = value
}

/** The container a take is recorded in, and the `Content-Type` its upload part carries. */
enum class AudioMime(val wire: String, val extension: String) {
    /** OGG / Opus (MediaRecorder on API 29+). */
    OGG("audio/ogg", "ogg"),

    /** AAC in MPEG-4 (MediaRecorder below API 29). */
    MP4("audio/mp4", "m4a"),
    ;

    companion object {
        /** The stored mime of a recording (parameters like `;codecs=opus` ignored), or null for one the app did not record. */
        fun parse(mime: String): AudioMime? {
            val base = mime.substringBefore(';').trim().lowercase()
            return entries.firstOrNull { it.wire == base }
        }
    }
}

/** How the server turned the transcript into lines. */
@Serializable
enum class SplitMode {
    /** At the user's taps (`breaks_ms`). */
    @SerialName("taps") TAPS,

    /** No taps: split by pauses, syllable balance and end rhymes. */
    @SerialName("auto") AUTO,

    /** No taps and no good boundary: one line. */
    @SerialName("single") SINGLE,
}

/** Where a voice take's text goes (`meta.target`). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface VoiceTakeTarget {
    /** A new freestyle snippet at a canvas position (canvas coordinates). */
    @Serializable
    @SerialName("new")
    data class New(
        @SerialName("position_x") val positionX: Double,
        @SerialName("position_y") val positionY: Double,
    ) : VoiceTakeTarget

    /** Appended after the last line of an existing snippet. */
    @Serializable
    @SerialName("append")
    data class Append(
        @SerialName("snippet_id") val snippetId: SnippetId,
    ) : VoiceTakeTarget
}

/**
 * The `meta` form field of `POST /api/canvases/{id}/voice-takes`. Built only through [of], which
 * keeps `breaks_ms` ascending, distinct and within `[0, duration_ms]`. [vocabularyHints] asks the
 * server to prompt the transcriber with the user's word bank (always sent).
 */
@Serializable
@ConsistentCopyVisibility
data class VoiceTakeMeta private constructor(
    @SerialName("breaks_ms") val breaksMs: List<Long>,
    @SerialName("duration_ms") val durationMs: Long,
    val target: VoiceTakeTarget,
    @SerialName("vocabulary_hints") val vocabularyHints: Boolean = true,
) {
    companion object {
        /** Breaks outside the take are dropped, the rest sorted and de-duplicated; a negative duration is 0. */
        fun of(
            breaksMs: Collection<Long>,
            durationMs: Long,
            target: VoiceTakeTarget,
            vocabularyHints: Boolean = true,
        ): VoiceTakeMeta {
            val duration = durationMs.coerceAtLeast(0)
            val breaks = breaksMs.filter { it in 0..duration }.distinct().sorted()
            return VoiceTakeMeta(breaks, duration, target, vocabularyHints)
        }
    }
}

/**
 * A recording attached to a snippet (no audio bytes; those come from the audio route). The fields
 * after [createdAt] came with the voice dataset (migration 007); old rows and old servers leave
 * them out, so each has an empty default. Read the verification through [verification], which is
 * set only when both wire fields are.
 */
@Serializable
data class Recording(
    val id: RecordingId,
    @SerialName("snippet_id") val snippetId: SnippetId,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("byte_size") val byteSize: Long,
    val transcript: String,
    @SerialName("split_mode") val splitMode: SplitMode,
    @SerialName("created_at") val createdAt: String,
    /** `transcript.split("\n")` as the server sent it; empty from an old server (see [referenceLines]). */
    val lines: List<String> = emptyList(),
    /** True when [words] and [lineSpans] are stored for this take. */
    @SerialName("has_timings") val hasTimings: Boolean = false,
    /** The STT words of the take, in service order. */
    val words: List<TimedWord>? = null,
    /** Per assembled line, the `[start, end)` indices into [words]. */
    @SerialName("line_spans") val lineSpans: List<List<Int>>? = null,
    val language: String? = null,
    /** The exact prompt sent to the transcriber, or null when none was sent. */
    @SerialName("initial_prompt") val initialPrompt: String? = null,
    @SerialName("verified_text") val verifiedText: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
) {
    /** The user's checked transcript, or null when the take is not checked. */
    val verification: Verification?
        get() = if (verifiedText != null && verifiedAt != null) Verification(verifiedText, verifiedAt) else null

    val isChecked: Boolean get() = verification != null

    /** The Whisper lines: [lines], or the transcript split at newlines when the server sent none. */
    val referenceLines: List<String> get() = lines.ifEmpty { transcript.split("\n") }
}

/** 201 response of the voice-takes route. */
@Serializable
data class VoiceTakeResponse(
    /** The new snippet, or the snippet the lines were appended to. */
    val snippet: Snippet,
    val recording: Recording,
    val lines: List<String>,
    @SerialName("split_mode") val splitMode: SplitMode,
    /** True when [snippet] was created by this take. */
    val created: Boolean,
)
