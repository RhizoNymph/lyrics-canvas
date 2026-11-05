package dev.nymph.lyrics.logic.wordbank

import dev.nymph.lyrics.logic.tags.isExprSpace
import java.text.Normalizer

/** Longest word bank text, in code points after normalizing (the server's `varchar(64)`). */
const val MAX_WORD_LENGTH: Int = 64

/** Most space-separated words in one entry (a short phrase). */
const val MAX_WORDS_PER_ENTRY: Int = 4

/**
 * A word bank text that passed [validateWordText]: normalized (NFC, stripped, whitespace runs
 * collapsed to one space) and within the rules. Only the validator builds one, so a request
 * carrying it never sends a text the server is known to refuse.
 */
@JvmInline
value class BankWordText internal constructor(val value: String) {
    override fun toString(): String = value
}

/** Why a text is not a valid word bank entry (checked in this order). */
sealed interface WordTextProblem {
    /** Nothing left after normalizing. */
    data object Empty : WordTextProblem

    /** More than [MAX_WORD_LENGTH] code points. */
    data object TooLong : WordTextProblem

    /** More than [MAX_WORDS_PER_ENTRY] words. */
    data object TooManyWords : WordTextProblem

    /** The first character that is not a letter, apostrophe (`'` `’`), hyphen or space. */
    data class InvalidCharacter(val character: String) : WordTextProblem

    /** Only apostrophes, hyphens and spaces. */
    data object NoLetter : WordTextProblem
}

sealed interface WordTextCheck {
    data class Valid(val text: BankWordText) : WordTextCheck

    data class Invalid(val problem: WordTextProblem) : WordTextCheck
}

private const val APOSTROPHE: Int = '\''.code
private const val RIGHT_SINGLE_QUOTE: Int = '’'.code
private const val HYPHEN: Int = '-'.code
private const val SPACE: Int = ' '.code

/**
 * The server's normalization: Unicode NFC, ends stripped and every internal whitespace run
 * collapsed to one space (Python's `" ".join(text.split())`, so `str.isspace` decides).
 */
fun normalizeWordText(raw: String): String {
    val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
    val out = StringBuilder(nfc.length)
    var pendingSpace = false
    nfc.codePoints().forEach { cp ->
        if (isExprSpace(cp)) {
            pendingSpace = out.isNotEmpty()
        } else {
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

/** Letters are Unicode `L*` (Python's `str.isalpha`). */
private fun isLetter(cp: Int): Boolean = Character.isLetter(cp)

private fun isAllowed(cp: Int): Boolean = isLetter(cp) || cp == APOSTROPHE || cp == RIGHT_SINGLE_QUOTE || cp == HYPHEN || cp == SPACE

/**
 * The contract's word text rules (docs/features/word_bank.md): after [normalizeWordText], 1–64
 * code points, at most 4 words, only letters, apostrophes, hyphens and spaces, at least one
 * letter. Case is kept. The server stays authoritative.
 */
fun validateWordText(raw: String): WordTextCheck {
    val text = normalizeWordText(raw)
    val codePoints = text.codePoints().toArray()
    val problem = when {
        codePoints.isEmpty() -> WordTextProblem.Empty
        codePoints.size > MAX_WORD_LENGTH -> WordTextProblem.TooLong
        codePoints.count { it == SPACE } + 1 > MAX_WORDS_PER_ENTRY -> WordTextProblem.TooManyWords
        else -> codePoints.firstOrNull { !isAllowed(it) }?.let { WordTextProblem.InvalidCharacter(String(Character.toChars(it))) }
            ?: if (codePoints.none(::isLetter)) WordTextProblem.NoLetter else null
    }
    return if (problem == null) WordTextCheck.Valid(BankWordText(text)) else WordTextCheck.Invalid(problem)
}

fun isValidWordText(raw: String): Boolean = validateWordText(raw) is WordTextCheck.Valid
