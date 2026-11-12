package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A word or short phrase in the user's word bank (`BankWordResponse`). [tags] are the user-wide
 * tags, sorted by lowercase name (docs/features/word_bank.md).
 */
@Serializable
data class BankWord(
    val id: BankWordId,
    val text: String,
    val tags: List<Tag> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    /** Content version (docs/features/sync_versions.md): +1 per content change, server-side only. */
    val version: Int = 1,
)

/** Body of `POST /api/word-bank`: 1–200 words, 0–20 tags linked to every created and existing word. */
@Serializable
data class WordBankAddRequest(
    val words: List<String>,
    @SerialName("tag_ids") val tagIds: List<TagId> = emptyList(),
)

/** One word of an offline add: its text and the client-chosen id (an identical retry comes back in `created`). */
@Serializable
data class NewBankWord(
    val text: String,
    val id: BankWordId,
)

/**
 * Body of `POST /api/word-bank` from the sync engine: every item carries a client id. A text
 * already banked under another id comes back in `existing` (not a conflict).
 */
@Serializable
data class WordBankSyncAddRequest(
    val words: List<NewBankWord>,
    @SerialName("tag_ids") val tagIds: List<TagId> = emptyList(),
)

/** Response of `POST /api/word-bank`: new words and ones already banked (stored spelling), both in input order. */
@Serializable
data class WordBankAddResponse(
    val created: List<BankWord> = emptyList(),
    val existing: List<BankWord> = emptyList(),
)

/** Body of `PUT /api/word-bank/{id}`. */
@Serializable
data class WordBankRenameRequest(
    val text: String,
    @SerialName("base_version") val baseVersion: Int? = null,
)

/** Body of `POST /api/word-bank/delete` (1–500 ids; foreign ids are ignored). */
@Serializable
data class WordBankDeleteRequest(
    val ids: List<BankWordId>,
    /** Word id to the version each delete is based on; any mismatch is 409 and nothing changes. */
    @SerialName("base_versions") val baseVersions: Map<String, Int>? = null,
)

/** Whether `POST /api/word-bank/tags` adds or removes the tag. */
@Serializable
enum class TagLinkOp {
    @SerialName("link") LINK,
    @SerialName("unlink") UNLINK,
}

/** Body of `POST /api/word-bank/tags` (1–500 words; idempotent). */
@Serializable
data class WordBankTagRequest(
    @SerialName("word_ids") val wordIds: List<BankWordId>,
    @SerialName("tag_id") val tagId: TagId,
    val op: TagLinkOp,
    @SerialName("base_versions") val baseVersions: Map<String, Int>? = null,
)

/** Response of `POST /api/word-bank/tags`: the updated words. */
@Serializable
data class WordBankTagResponse(
    val words: List<BankWord> = emptyList(),
)

/** What "rhymes with" ranks the bank against: one banked word, or a typed text. */
sealed interface BankRhymeAnchor {
    data class Word(val id: BankWordId) : BankRhymeAnchor

    /** Any non-blank text (the server phoneticises it). */
    data class Text(val text: String) : BankRhymeAnchor
}

/**
 * Body of `POST /api/word-bank/rhymes`. Exactly one of [wordId] and [text] is set, which only
 * [of] can build. [tagFilter] is a normalized expression or null (no filter).
 */
@ConsistentCopyVisibility
@Serializable
data class BankRhymesRequest private constructor(
    @SerialName("word_id") val wordId: BankWordId? = null,
    val text: String? = null,
    @SerialName("tag_filter") val tagFilter: String? = null,
    @SerialName("min_score") val minScore: Double = DEFAULT_MIN_SCORE,
    val k: Int = 2,
    val limit: Int = 100,
) {
    companion object {
        const val DEFAULT_MIN_SCORE: Double = 0.5

        fun of(
            anchor: BankRhymeAnchor,
            tagFilter: String? = null,
            minScore: Double = DEFAULT_MIN_SCORE,
            k: Int = 2,
            limit: Int = 100,
        ): BankRhymesRequest = when (anchor) {
            is BankRhymeAnchor.Word -> BankRhymesRequest(wordId = anchor.id, tagFilter = tagFilter, minScore = minScore, k = k, limit = limit)
            is BankRhymeAnchor.Text -> BankRhymesRequest(text = anchor.text, tagFilter = tagFilter, minScore = minScore, k = k, limit = limit)
        }
    }
}

/** The phoneticised anchor of a rhymes-with query. */
@Serializable
data class BankRhymeAnchorInfo(
    val text: String,
    @SerialName("rhyme_key") val rhymeKey: List<String> = emptyList(),
    @SerialName("syllable_count") val syllableCount: Int = 0,
)

@Serializable
data class BankRhymeMatch(
    val word: BankWord,
    val score: Double,
    @SerialName("match_depth") val matchDepth: Int,
)

/** Response of `POST /api/word-bank/rhymes`: score desc, then depth desc, then lowercase text. */
@Serializable
data class BankRhymesResponse(
    val anchor: BankRhymeAnchorInfo,
    val matches: List<BankRhymeMatch> = emptyList(),
)

/** Body of `POST /api/word-bank/families`. */
@Serializable
data class BankFamiliesRequest(
    @SerialName("tag_filter") val tagFilter: String? = null,
    @SerialName("min_score") val minScore: Double = DEFAULT_MIN_SCORE,
    val k: Int = 2,
) {
    companion object {
        const val DEFAULT_MIN_SCORE: Double = 0.8
    }
}

/** A rhyme family: at least two words, the medoid ([label]) first. */
@Serializable
data class BankFamily(
    val label: String,
    @SerialName("rhyme_key") val rhymeKey: List<String> = emptyList(),
    val words: List<BankWord>,
    val cohesion: Double,
)

/** Response of `POST /api/word-bank/families`: every filtered word is in one family or in [unmatched]. */
@Serializable
data class BankFamiliesResponse(
    val families: List<BankFamily> = emptyList(),
    val unmatched: List<BankWord> = emptyList(),
)

/** One related-word result; [bankWordId] is set when the user already has the word. */
@Serializable
data class RelatedWord(
    val text: String,
    val similarity: Double,
    @SerialName("bank_word_id") val bankWordId: BankWordId? = null,
)

/** Response of `GET /api/word-bank/related?q=&limit=`: similarity desc. */
@Serializable
data class RelatedWordsResponse(
    val query: String,
    val results: List<RelatedWord> = emptyList(),
)

/**
 * The `error` codes the word bank routes add to `{"detail": {"error": code, ...}}` (the tag codes
 * `tag_not_found` and `invalid_tag_expression` are reused and stay in [TagErrorCode]).
 * Unknown codes parse to null.
 */
enum class WordBankErrorCode(val wire: String) {
    /** 422: a word breaks the text rules; for a batch, `detail.index` is the first bad position. */
    INVALID_WORD("invalid_word"),

    /** 409: a rename collides with another of the user's words (ignoring case). */
    WORD_EXISTS("word_exists"),

    /** 404: the id is not the user's. */
    WORD_NOT_FOUND("word_not_found"),

    /** 422: families over more than 1000 (filtered) words, or a batch over its limit. */
    TOO_MANY_WORDS("too_many_words"),

    /** 503 with `Retry-After`: the related-word index is still being built. */
    RELATED_WORDS_WARMING("related_words_warming"),

    /** 503: the embedding model cannot be loaded. */
    RELATED_WORDS_UNAVAILABLE("related_words_unavailable"),
    ;

    companion object {
        fun parse(code: String?): WordBankErrorCode? = entries.firstOrNull { it.wire == code }
    }
}
