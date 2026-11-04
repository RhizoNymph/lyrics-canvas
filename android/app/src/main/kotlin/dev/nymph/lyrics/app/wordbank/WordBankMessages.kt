package dev.nymph.lyrics.app.wordbank

import dev.nymph.lyrics.app.tags.userMessage
import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.wordbank.AddPlan
import dev.nymph.lyrics.logic.wordbank.MAX_ADD_BATCH
import dev.nymph.lyrics.logic.wordbank.MAX_RELATED_QUERY_LENGTH
import dev.nymph.lyrics.logic.wordbank.MAX_WORDS_PER_ENTRY
import dev.nymph.lyrics.logic.wordbank.MAX_WORD_LENGTH
import dev.nymph.lyrics.logic.wordbank.RelatedQueryCheck
import dev.nymph.lyrics.logic.wordbank.WordBankError
import dev.nymph.lyrics.logic.wordbank.WordTextProblem

const val RELATED_WARMING_MESSAGE: String = "Building related-word index…"

const val RELATED_UNAVAILABLE_MESSAGE: String = "Related words are unavailable on this server"

/** Why a text cannot be a word bank entry. */
fun WordTextProblem.userMessage(): String = when (this) {
    WordTextProblem.Empty -> "Enter a word."
    WordTextProblem.TooLong -> "Words can be at most $MAX_WORD_LENGTH characters."
    WordTextProblem.TooManyWords -> "An entry can have at most $MAX_WORDS_PER_ENTRY words."
    is WordTextProblem.InvalidCharacter -> "\"$character\" is not allowed: use letters, apostrophes, hyphens and spaces."
    WordTextProblem.NoLetter -> "An entry needs at least one letter."
}

/** The local refusal of an add box submit, or null when it can be sent (or there is nothing to send). */
fun AddPlan.problemMessage(): String? = when (this) {
    AddPlan.Nothing, is AddPlan.Ready -> null
    is AddPlan.Invalid -> "Word ${index + 1} (\"$piece\"): ${problem.userMessage()}"
    is AddPlan.TooMany -> "At most $MAX_ADD_BATCH words at a time ($count given)."
}

fun RelatedQueryCheck.problemMessage(): String? = when (this) {
    is RelatedQueryCheck.Ok -> null
    RelatedQueryCheck.Empty -> "Type a word, phrase or theme."
    RelatedQueryCheck.TooLong -> "Search text can be at most $MAX_RELATED_QUERY_LENGTH characters."
}

/** The text shown for a failed word bank call, by server code where there is one. */
fun WordBankError.userMessage(): String = when (this) {
    is WordBankError.InvalidWord -> index?.let { "The server rejected word ${it + 1}." } ?: "The server rejected this word."
    WordBankError.WordExists -> "You already have that word."
    WordBankError.WordNotFound -> "That word is no longer in your bank."
    WordBankError.TooManyWords -> "Too many words for this. Narrow the filter and try again."
    is WordBankError.RelatedWarming -> RELATED_WARMING_MESSAGE
    WordBankError.RelatedUnavailable -> RELATED_UNAVAILABLE_MESSAGE
    is WordBankError.TagRejected -> error.userMessage()
    WordBankError.SessionExpired -> SESSION_EXPIRED
    is WordBankError.Http -> message
    is WordBankError.Network -> "Can't reach the server. Check your connection."
    is WordBankError.Unexpected -> "Unexpected response from the server."
}
