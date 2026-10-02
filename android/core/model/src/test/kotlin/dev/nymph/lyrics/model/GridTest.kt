package dev.nymph.lyrics.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GridTest {
    @Test
    fun `cell round-trips through json`() {
        val cell = GridCell(syllable = "or", stress = 1, word_index = 0)
        assertEquals(cell, Json.decodeFromString<GridCell>(Json.encodeToString(GridCell.serializer(), cell)))
    }
}
