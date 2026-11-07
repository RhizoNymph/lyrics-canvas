package dev.nymph.lyrics.logic.voice

import dev.nymph.lyrics.model.voice.Recording

/** The server's limit on a checked transcript, after normalising. */
const val MAX_VERIFICATION_CODE_POINTS: Int = 10_000

/** Why a correction cannot be saved, found before it is sent (the server would answer `invalid_verification`). */
sealed interface VerificationInputError {
    /** Nothing but whitespace. */
    data object Empty : VerificationInputError

    data class TooLong(val codePoints: Int, val max: Int) : VerificationInputError
}

sealed interface VerificationInput {
    /** [text] is normalised and within the limits. */
    data class Valid(val text: String) : VerificationInput

    data class Invalid(val error: VerificationInputError) : VerificationInput
}

/** What the correction field starts with: the checked text, or the Whisper lines joined by `\n`. */
fun initialCorrectionText(recording: Recording): String =
    recording.verification?.text ?: recording.referenceLines.joinToString("\n")

/** Python's `str.isspace`, which the server's `rstrip` uses. */
private fun isPySpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

private fun String.trimEndPy(): String {
    var end = length
    while (end > 0) {
        val cp = codePointBefore(end)
        if (!isPySpace(cp)) break
        end -= Character.charCount(cp)
    }
    return substring(0, end)
}

/**
 * The server's normalisation of a checked transcript: CRLF / CR to LF, trailing whitespace stripped
 * on every line, blank lines dropped at both ends, runs of blank lines collapsed into one.
 */
fun normalizeVerification(text: String): String {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEndPy() }
    val first = lines.indexOfFirst { it.isNotEmpty() }
    if (first < 0) return ""
    val last = lines.indexOfLast { it.isNotEmpty() }
    val out = ArrayList<String>()
    for (line in lines.subList(first, last + 1)) {
        if (line.isEmpty() && out.lastOrNull()?.isEmpty() == true) continue
        out += line
    }
    return out.joinToString("\n")
}

/** Normalise [text] and check it against the server's rules. */
fun validateVerification(text: String): VerificationInput {
    val normalized = normalizeVerification(text)
    if (normalized.isEmpty()) return VerificationInput.Invalid(VerificationInputError.Empty)
    val length = normalized.codePointCount(0, normalized.length)
    if (length > MAX_VERIFICATION_CODE_POINTS) {
        return VerificationInput.Invalid(VerificationInputError.TooLong(length, MAX_VERIFICATION_CODE_POINTS))
    }
    return VerificationInput.Valid(normalized)
}
