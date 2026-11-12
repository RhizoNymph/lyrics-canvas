package dev.nymph.lyrics.app.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
@Serializable
data object AuthRoute

@Serializable
data object CanvasListRoute

/** A canvas; [focusSnippetId] (a search hit) opens it with that snippet selected and centred. */
@Serializable
data class CanvasRoute(val canvasId: String, val focusSnippetId: String? = null)

/** The grid editor for one snippet of the canvas below it on the back stack. */
@Serializable
data class EditorRoute(val canvasId: String, val snippetId: String)

/** The word bank, opened from the canvas below it on the back stack (drafts become snippets there). */
@Serializable
data class WordBankRoute(val canvasId: String)

/** Every change that is parked (not synced), from the sync status indicator. */
@Serializable
data object NotSyncedRoute

/** One parked change: mine next to the server's, and the choices. [opId] is its outbox entry. */
@Serializable
data class ReviewRoute(val opId: Long)

/** Check transcript for one recording of the snippet open in the editor below it. */
@Serializable
data class CheckTranscriptRoute(val snippetId: String, val recordingId: String)
