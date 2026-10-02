package dev.nymph.lyrics.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One beat-grid cell; mirrors `GridCell` in frontend/src/types/index.ts.
 *
 * A rest is `GridCell(rest = true)`. `word` is the surface word (as typed, incl. punctuation)
 * this syllable belongs to, so `raw_text` can be rebuilt after rearranging.
 */
@Serializable
data class GridCell(
    val syllable: String? = null,
    /** 0 = unstressed, 1 = primary, 2 = secondary. */
    val stress: Int? = null,
    @SerialName("word_index") val wordIndex: Int? = null,
    val rest: Boolean? = null,
    val word: String? = null,
) {
    /** True for a rest cell (`rest: true`); a missing flag is a sounding cell. */
    val isRest: Boolean get() = rest == true

    companion object {
        /** The canonical rest cell: `{"rest": true}`. */
        val REST: GridCell = GridCell(rest = true)
    }
}

/** One line of a beat grid. */
@Serializable
data class GridLine(
    val cells: List<GridCell>,
    @SerialName("syllable_count") val syllableCount: Int = 0,
    @SerialName("raw_text") val rawText: String = "",
)

/** A snippet's beat grid; also the response body of `POST /api/nlp/syllabify`. */
@Serializable
data class GridData(
    val lines: List<GridLine> = emptyList(),
)
