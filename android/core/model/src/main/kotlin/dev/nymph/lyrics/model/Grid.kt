package dev.nymph.lyrics.model

import kotlinx.serialization.Serializable

/** One beat-grid cell; mirrors frontend/src/types/index.ts GridCell. */
@Serializable
data class GridCell(
    val syllable: String? = null,
    val stress: Int? = null,
    val word_index: Int? = null,
    val rest: Boolean? = null,
    val word: String? = null,
)
