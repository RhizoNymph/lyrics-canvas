package dev.nymph.lyrics.app.tags

import dev.nymph.lyrics.app.ui.SESSION_EXPIRED
import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.TagExprError
import dev.nymph.lyrics.logic.tags.TagNameProblem
import dev.nymph.lyrics.logic.tags.defaultMessage
import dev.nymph.lyrics.model.TagErrorCode

/** An expression error for people: the message and the 1-based character it points at. */
fun TagExprError.userMessage(): String = "$message (at ${position + 1})"

/** The text shown for a failed tag, preset or link call, by server code where there is one. */
fun TagError.userMessage(): String = when (this) {
    is TagError.Rejected -> when (code) {
        TagErrorCode.TAG_NAME_TAKEN -> "A tag with this name already exists."
        TagErrorCode.TAG_NOT_FOUND -> "That tag no longer exists."
        TagErrorCode.PRESET_NAME_TAKEN -> "A preset with this name already exists."
        TagErrorCode.PRESET_NOT_FOUND -> "That preset no longer exists."
        TagErrorCode.INVALID_TAG_EXPRESSION ->
            // The server's wording is not part of the contract: the client's message for its code.
            "The server rejected the filter" + (expressionError?.let { ": ${it.copy(message = defaultMessage(it.code)).userMessage()}." } ?: ".")
        TagErrorCode.EMPTY_PRESET -> EMPTY_PRESET_MESSAGE
        TagErrorCode.INVALID_TAG_NAME -> "The server rejected this tag name."
        TagErrorCode.INVALID_COLOR -> "The server rejected this colour."
        TagErrorCode.INVALID_PRESET_NAME -> "The server rejected this preset name."
        TagErrorCode.PRESET_EXPRESSION_TOO_LONG -> "Renaming would make a preset's filter too long. Use a shorter name."
    }
    TagError.SessionExpired -> SESSION_EXPIRED
    is TagError.Http -> message
    is TagError.Network -> "Can't reach the server. Check your connection."
    is TagError.Unexpected -> "Unexpected response from the server."
}

fun TagNameProblem.userMessage(): String = when (this) {
    TagNameProblem.EMPTY -> "Enter a name."
    TagNameProblem.TOO_LONG -> "Names can be at most 100 characters."
    TagNameProblem.CONTROL_CHARACTER -> "Names cannot contain control characters."
}

const val EMPTY_PRESET_MESSAGE: String = "A preset needs a filter. Type one first."

/** A local refusal to save or overwrite a preset with an invalid filter. */
fun invalidFilterMessage(error: TagExprError): String = "The filter is invalid: ${error.userMessage()}."
