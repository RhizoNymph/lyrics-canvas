package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.toTagError
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.WordBankErrorCode
import dev.nymph.lyrics.model.error.ApiError

/** The wait when a `related_words_warming` response has no usable `Retry-After`. */
const val DEFAULT_RELATED_RETRY_AFTER_SECONDS: Int = 10

/** Why a word bank call failed; UI state, never thrown. Every server code has its own case. */
sealed interface WordBankError {
    /** 422 `invalid_word`; [index] is the first bad word of a batch (null for a rename or a related query). */
    data class InvalidWord(val index: Int?, val serverMessage: String?) : WordBankError

    /** 409 `word_exists`: a rename collides with another word (ignoring case). */
    data object WordExists : WordBankError

    /** 404 `word_not_found`: the word is gone or not the user's. */
    data object WordNotFound : WordBankError

    /** 422 `too_many_words`: families over more than 1000 words, or a batch over its limit. */
    data object TooManyWords : WordBankError

    /** 503 `related_words_warming`: retry after [retryAfterSeconds]. */
    data class RelatedWarming(val retryAfterSeconds: Int) : WordBankError

    /** 503 `related_words_unavailable`: the embedding model cannot be loaded. */
    data object RelatedUnavailable : WordBankError

    /** A tag code (`tag_not_found`, `invalid_tag_expression`, ...). */
    data class TagRejected(val error: TagError.Rejected) : WordBankError

    /** 401: the app is signing out. */
    data object SessionExpired : WordBankError

    /** Any other HTTP failure (no code, or one this client does not know). */
    data class Http(val status: Int, val message: String) : WordBankError

    /** No response. */
    data class Network(val message: String) : WordBankError

    /** A 2xx response the app could not read. */
    data class Unexpected(val message: String) : WordBankError
}

fun toWordBankError(error: ApiError): WordBankError {
    when (val code = WordBankErrorCode.parse(error.code)) {
        WordBankErrorCode.INVALID_WORD -> return WordBankError.InvalidWord((error as? ApiError.Validation)?.index, error.message)
        WordBankErrorCode.WORD_EXISTS -> return WordBankError.WordExists
        WordBankErrorCode.WORD_NOT_FOUND -> return WordBankError.WordNotFound
        WordBankErrorCode.TOO_MANY_WORDS -> return WordBankError.TooManyWords
        WordBankErrorCode.RELATED_WORDS_WARMING -> return WordBankError.RelatedWarming(
            (error as? ApiError.Server)?.retryAfterSeconds ?: DEFAULT_RELATED_RETRY_AFTER_SECONDS,
        )
        WordBankErrorCode.RELATED_WORDS_UNAVAILABLE -> return WordBankError.RelatedUnavailable
        null -> Unit
    }
    return when (val tag = toTagError(error)) {
        is TagError.Rejected -> WordBankError.TagRejected(tag)
        TagError.SessionExpired -> WordBankError.SessionExpired
        is TagError.Http -> WordBankError.Http(tag.status, tag.message)
        is TagError.Network -> WordBankError.Network(tag.message)
        is TagError.Unexpected -> WordBankError.Unexpected(tag.message)
    }
}

/** True for a tag that no longer exists (the picker drops it). */
val WordBankError.isTagNotFound: Boolean
    get() = this is WordBankError.TagRejected && error.code == TagErrorCode.TAG_NOT_FOUND
