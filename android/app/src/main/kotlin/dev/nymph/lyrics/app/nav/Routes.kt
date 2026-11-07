package dev.nymph.lyrics.app.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
@Serializable
data object AuthRoute

@Serializable
data object CanvasListRoute

@Serializable
data class CanvasRoute(val canvasId: String)

/** The grid editor for one snippet of the canvas below it on the back stack. */
@Serializable
data class EditorRoute(val canvasId: String, val snippetId: String)

/** The word bank, opened from the canvas below it on the back stack (drafts become snippets there). */
@Serializable
data class WordBankRoute(val canvasId: String)

/** Check transcript for one recording of the snippet open in the editor below it. */
@Serializable
data class CheckTranscriptRoute(val snippetId: String, val recordingId: String)
