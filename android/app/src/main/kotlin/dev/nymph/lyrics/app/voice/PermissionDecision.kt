package dev.nymph.lyrics.app.voice

/** What the host does when the voice modal asks for the microphone permission. */
enum class PermissionAction {
    /** Already granted: start recording. */
    PROCEED,

    /** Explain first (the system recommends a rationale before asking). */
    SHOW_RATIONALE,

    /** Show the system permission dialog. */
    LAUNCH_REQUEST,
}

/**
 * The first ask of a session ([firstAsk]) shows a rationale when the system recommends one;
 * an ask the user already confirmed (rationale or denied screen) goes straight to the dialog.
 */
fun permissionAction(granted: Boolean, shouldShowRationale: Boolean, firstAsk: Boolean): PermissionAction = when {
    granted -> PermissionAction.PROCEED
    firstAsk && shouldShowRationale -> PermissionAction.SHOW_RATIONALE
    else -> PermissionAction.LAUNCH_REQUEST
}

/**
 * After the system dialog denied the permission: when the system no longer recommends a
 * rationale, the user chose "don't ask again" (or policy forbids it), so only the app settings
 * can grant it.
 */
fun deniedPermanently(shouldShowRationaleAfterDenial: Boolean): Boolean = !shouldShowRationaleAfterDenial
