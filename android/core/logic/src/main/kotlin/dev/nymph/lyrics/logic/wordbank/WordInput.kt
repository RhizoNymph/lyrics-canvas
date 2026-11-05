package dev.nymph.lyrics.logic.wordbank

import java.util.Locale

/** Most words one `POST /api/word-bank` may carry. */
const val MAX_ADD_BATCH: Int = 200

/**
 * The add box's split: commas and newlines (`\n`, `\r`) separate entries, spaces do not (so
 * phrases are allowed). Each piece is trimmed; blank pieces are dropped.
 */
fun splitWordInput(raw: String): List<String> =
    raw.split(',', '\n', '\r').map(String::trim).filter { it.isNotEmpty() && normalizeWordText(it).isNotEmpty() }

/** What submitting the add box (or an editor selection) would send. */
sealed interface AddPlan {
    /** No words in the input. */
    data object Nothing : AddPlan

    /** Piece [index] (0-based, among the non-blank pieces) breaks the rules; nothing is sent. */
    data class Invalid(val index: Int, val piece: String, val problem: WordTextProblem) : AddPlan

    /** More than [MAX_ADD_BATCH] distinct words. */
    data class TooMany(val count: Int) : AddPlan

    /** Normalized, distinct ignoring case (first spelling wins), in input order. */
    data class Ready(val words: List<BankWordText>) : AddPlan
}

fun planAdd(raw: String): AddPlan {
    val pieces = splitWordInput(raw)
    if (pieces.isEmpty()) return AddPlan.Nothing
    val seen = HashSet<String>()
    val words = ArrayList<BankWordText>()
    pieces.forEachIndexed { index, piece ->
        when (val check = validateWordText(piece)) {
            is WordTextCheck.Invalid -> return AddPlan.Invalid(index, piece, check.problem)
            is WordTextCheck.Valid -> if (seen.add(check.text.value.lowercase(Locale.ROOT))) words += check.text
        }
    }
    return if (words.size > MAX_ADD_BATCH) AddPlan.TooMany(words.size) else AddPlan.Ready(words)
}
