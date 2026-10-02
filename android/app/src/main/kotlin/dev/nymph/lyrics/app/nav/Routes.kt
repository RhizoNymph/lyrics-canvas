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
