package dev.nymph.lyrics.data

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A downloaded file and the name the server suggested for it (null when it sent none). */
data class DownloadedFile(val file: File, val suggestedName: String?)

private val EXTENDED_NAME = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
private val QUOTED_NAME = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
private val BARE_NAME = Regex("""filename\s*=\s*([^;"\s]+)""", RegexOption.IGNORE_CASE)

/**
 * The file name of a `Content-Disposition` header: the RFC 5987 `filename*=UTF-8''...` form
 * when present, else `filename=`. Only the last path segment is kept (no directories), and a
 * blank name is null.
 */
fun contentDispositionFileName(header: String?): String? {
    if (header == null) return null
    val extended = EXTENDED_NAME.find(header)?.groupValues?.get(1)?.trim()?.let(::decodeExtended)
    val plain = QUOTED_NAME.find(header)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
        ?: BARE_NAME.find(header)?.groupValues?.get(1)
    val name = (extended ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
    return name?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

/** `UTF-8''Nuit%20%C3%A9t%C3%A9.mid` -> `Nuit été.mid`; another charset or a bad escape is null. */
private fun decodeExtended(value: String): String? {
    val parts = value.split("'", limit = 3)
    if (parts.size != 3 || !parts[0].equals("UTF-8", ignoreCase = true)) return null
    return try {
        URLDecoder.decode(parts[2].replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        null
    }
}
