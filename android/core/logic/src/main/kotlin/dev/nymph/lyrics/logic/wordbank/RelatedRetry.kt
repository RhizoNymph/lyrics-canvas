package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.codePointLength

/** At most this many retries while the related-word index is warming. */
const val MAX_RELATED_RETRIES: Int = 10

/** The longest related-words query, in code points after a strip. */
const val MAX_RELATED_QUERY_LENGTH: Int = 64

/**
 * The wait before retry number `retriesSoFar + 1` of a warming related-words query, honouring
 * `Retry-After` (clamped to 1–60 s), or null once [MAX_RELATED_RETRIES] retries were made.
 */
fun relatedRetryDelayMs(retriesSoFar: Int, retryAfterSeconds: Int): Long? {
    if (retriesSoFar >= MAX_RELATED_RETRIES) return null
    return retryAfterSeconds.coerceIn(1, 60) * 1_000L
}

sealed interface RelatedQueryCheck {
    data class Ok(val query: String) : RelatedQueryCheck

    data object Empty : RelatedQueryCheck

    data object TooLong : RelatedQueryCheck
}

/** The related search's `q` rule: 1–64 code points after a strip; any characters (a phrase or theme). */
fun checkRelatedQuery(raw: String): RelatedQueryCheck {
    val q = raw.trim()
    return when {
        q.isEmpty() -> RelatedQueryCheck.Empty
        codePointLength(q) > MAX_RELATED_QUERY_LENGTH -> RelatedQueryCheck.TooLong
        else -> RelatedQueryCheck.Ok(q)
    }
}
