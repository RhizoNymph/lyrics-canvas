package dev.nymph.lyrics.logic.score

import dev.nymph.lyrics.model.GridData

/** Grid line index -> new `raw_text` for every line whose `raw_text` differs (same line counts required). */
fun changedLineTexts(old: GridData?, new: GridData?): Map<Int, String> {
    val oldLines = old.scoreLines()
    val newLines = new.scoreLines()
    require(oldLines.size == newLines.size) { "changedLineTexts needs grids with the same number of lines" }
    return oldLines.indices.filter { oldLines[it].rawText != newLines[it].rawText }.associateWith { newLines[it].rawText }
}

/**
 * Replace the text lines that are grid lines in [replacements] (the n-th non-blank line is grid line
 * n); blank and other lines are kept byte for byte (spec section 13). `TextMismatch` for an index
 * the text does not have.
 */
fun spliceLineTexts(rawText: String, replacements: Map<Int, String>): ScoreResult<String> {
    val pieces = rawText.split("\n").toMutableList()
    var gridIndex = 0
    for (position in pieces.indices) {
        if (pieces[position].isBlank()) continue
        replacements[gridIndex]?.let { pieces[position] = it }
        gridIndex++
    }
    val missing = replacements.keys.filter { it >= gridIndex || it < 0 }
    if (missing.isNotEmpty()) return ScoreResult.Err(ScoreError.TextMismatch(missing.min(), gridIndex))
    return ScoreResult.Ok(pieces.joinToString("\n"))
}
