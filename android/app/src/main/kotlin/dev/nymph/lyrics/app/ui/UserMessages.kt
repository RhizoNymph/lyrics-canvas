package dev.nymph.lyrics.app.ui

import dev.nymph.lyrics.model.error.ApiError

/** The text a person sees for a failed API call (snackbars, error states). */
fun ApiError.userMessage(): String = when (this) {
    is ApiError.Unauthorized -> if (status == 401) SESSION_EXPIRED else "Not signed in."
    is ApiError.NotFound -> message
    is ApiError.Validation -> message
    is ApiError.Server -> message
    is ApiError.Network -> "Can't reach the server. Check your connection."
    is ApiError.Decoding -> "Unexpected response from the server."
}

const val SESSION_EXPIRED: String = "Your session has expired. Sign in again."

/** `#rrggbb` (the server's tag colours) as opaque ARGB, or null when it is not one. */
fun parseHexColor(hex: String): Long? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return 0xFF000000L or rgb
}
